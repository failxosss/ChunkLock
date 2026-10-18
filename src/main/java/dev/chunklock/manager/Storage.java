package dev.chunklock.manager;

import dev.chunklock.ChunkLockPlugin;
import dev.chunklock.model.ChunkPos;
import dev.chunklock.model.Dimension;
import dev.chunklock.model.Island;
import dev.chunklock.model.IslandPermission;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

/** Ukládání ostrovů do plugins/ChunkLock/data (jeden YAML na ostrov). */
public final class Storage {

    public record State(int nextSlot, int spacing, Map<UUID, UUID> active) {}

    private final ChunkLockPlugin plugin;
    private final Map<Dimension, File> islandsDirs = new java.util.EnumMap<>(Dimension.class);
    private final Map<Dimension, File> stateFiles = new java.util.EnumMap<>(Dimension.class);
    private final File backupsDir;
    private final File pendingFile;
    private final Object writeLock = new Object();

    public Storage(ChunkLockPlugin plugin) {
        this.plugin = plugin;
        File dataDir = new File(plugin.getDataFolder(), "data");
        this.backupsDir = new File(dataDir, "backups");
        this.pendingFile = new File(dataDir, "pending-delete.yml");
        for (Dimension d : Dimension.values()) {
            // overworld zůstává ve starých složkách (islands, state.yml), Nether a End mají vlastní
            File dir = new File(dataDir, d == Dimension.OVERWORLD ? "islands" : "islands-" + d.id());
            islandsDirs.put(d, dir);
            stateFiles.put(d, new File(dataDir, d == Dimension.OVERWORLD ? "state.yml" : "state-" + d.id() + ".yml"));
            if (!dir.exists() && !dir.mkdirs()) {
                plugin.getLogger().severe("Nelze vytvořit složku " + dir);
            }
        }
    }

    // ---------------------------------------------------------------- load

    public List<Island> loadIslands(Dimension dim) {
        List<Island> out = new ArrayList<>();
        File[] files = islandsDirs.get(dim).listFiles((dir, name) -> name.endsWith(".yml"));
        if (files == null) return out;
        for (File f : files) {
            try {
                out.add(read(f, dim));
            } catch (Exception ex) {
                plugin.getLogger().log(Level.SEVERE, "Nelze načíst ostrov " + f.getName(), ex);
            }
        }
        return out;
    }

    private Island read(File file, Dimension dim) {
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        UUID owner = UUID.fromString(file.getName().substring(0, file.getName().length() - 4));
        return read(y, owner, file, dim);
    }

    private Island read(YamlConfiguration y, UUID owner, File file, Dimension dim) {
        Island island = new Island(dim, null, owner, y.getString("owner-name", "?"), y.getInt("grid-x"),
                y.getInt("grid-z"));
        island.setDragonKilled(y.getBoolean("dragon-killed", false));
        if (y.contains("ref-y")) island.setRefY(y.getInt("ref-y"));

        for (String s : y.getStringList("unlocked")) {
            island.unlock(ChunkPos.parse(s));
        }
        if (island.unlockedCount() == 0) island.unlock(new ChunkPos(0, 0));
        island.setMobSpawning(y.getBoolean("mob-spawning", true));
        if (y.contains("source-x") && y.contains("source-z")) {
            island.setSource(y.getInt("source-x"), y.getInt("source-z"));
        }
        island.setCreated(y.contains("created") ? y.getLong("created") : guessCreated(owner, file));

        ConfigurationSection prog = y.getConfigurationSection("progress");
        if (prog != null) {
            for (String chunkKey : prog.getKeys(false)) {
                ConfigurationSection sec = prog.getConfigurationSection(chunkKey);
                if (sec == null) continue;
                ChunkPos pos = ChunkPos.parse(chunkKey);
                for (String mat : sec.getKeys(false)) {
                    Material m = Material.matchMaterial(mat);
                    if (m != null) island.setProgress(pos, m, sec.getInt(mat));
                }
            }
        }

        ConfigurationSection res = y.getConfigurationSection("resources");
        if (res != null) {
            for (String chunkKey : res.getKeys(false)) {
                ConfigurationSection sec = res.getConfigurationSection(chunkKey);
                if (sec == null) continue;
                Map<Material, Integer> counts = new EnumMap<>(Material.class);
                for (String mat : sec.getKeys(false)) {
                    Material m = Material.matchMaterial(mat);
                    int n = sec.getInt(mat);
                    if (m != null && n > 0) counts.put(m, n);
                }
                island.setResources(ChunkPos.parse(chunkKey), counts); // i prázdný sken = "už zjištěno"
            }
        }

        ConfigurationSection req = y.getConfigurationSection("required");
        if (req != null) {
            for (String chunkKey : req.getKeys(false)) {
                ConfigurationSection sec = req.getConfigurationSection(chunkKey);
                if (sec == null) continue;
                Map<Material, Integer> items = new LinkedHashMap<>();
                for (String mat : sec.getKeys(false)) {
                    Material m = Material.matchMaterial(mat);
                    int n = sec.getInt(mat);
                    if (m != null && n > 0) items.put(m, n);
                }
                if (!items.isEmpty()) island.setRequired(ChunkPos.parse(chunkKey), items);
            }
        }

        ConfigurationSection contrib = y.getConfigurationSection("contributions");
        if (contrib != null) {
            for (String uuidStr : contrib.getKeys(false)) {
                try {
                    int n = contrib.getInt(uuidStr);
                    if (n > 0) island.contributions().put(UUID.fromString(uuidStr), n);
                } catch (IllegalArgumentException ignored) {
                    // poškozený záznam
                }
            }
        }
        ConfigurationSection byChunk = y.getConfigurationSection("contributors");
        if (byChunk != null) {
            for (String chunkKey : byChunk.getKeys(false)) {
                ConfigurationSection sec = byChunk.getConfigurationSection(chunkKey);
                if (sec == null) continue;
                try {
                    ChunkPos pos = ChunkPos.parse(chunkKey);
                    Map<UUID, Integer> map = new HashMap<>();
                    for (String uuidStr : sec.getKeys(false)) {
                        int n = sec.getInt(uuidStr);
                        if (n > 0) map.put(UUID.fromString(uuidStr), n);
                    }
                    if (!map.isEmpty()) island.allContributors().put(pos.key(), map);
                } catch (IllegalArgumentException ignored) {
                    // poškozený záznam
                }
            }
        }

        ConfigurationSection mem = y.getConfigurationSection("members");
        if (mem != null) {
            for (String uuidStr : mem.getKeys(false)) {
                ConfigurationSection sec = mem.getConfigurationSection(uuidStr);
                if (sec == null) continue;
                List<IslandPermission> perms = new ArrayList<>();
                for (String p : sec.getStringList("permissions")) {
                    IslandPermission.parse(p).ifPresent(perms::add);
                }
                try {
                    island.addMember(UUID.fromString(uuidStr), sec.getString("name", "?"), perms);
                } catch (IllegalArgumentException ignored) {
                    // poškozený záznam
                }
            }
        }
        return island;
    }

    /** Starší ostrovy nemají uložené datum vytvoření - zkusíme první připojení hráče, pak čas souboru. */
    private long guessCreated(UUID owner, File file) {
        long first = Bukkit.getOfflinePlayer(owner).getFirstPlayed();
        if (first > 0) return first;
        try {
            return java.nio.file.Files.readAttributes(file.toPath(), java.nio.file.attribute.BasicFileAttributes.class)
                    .creationTime().toMillis();
        } catch (IOException | RuntimeException ex) {
            return System.currentTimeMillis();
        }
    }

    public State loadState(Dimension dim, int defaultSpacing) {
        YamlConfiguration y = YamlConfiguration.loadConfiguration(stateFiles.get(dim));
        Map<UUID, UUID> active = new HashMap<>();
        ConfigurationSection sec = y.getConfigurationSection("active");
        if (sec != null) {
            for (String key : sec.getKeys(false)) {
                try {
                    active.put(UUID.fromString(key), UUID.fromString(sec.getString(key, "")));
                } catch (IllegalArgumentException ignored) {
                    // poškozený záznam
                }
            }
        }
        return new State(y.getInt("next-slot", 0), y.getInt("spacing", defaultSpacing), active);
    }

    // ---------------------------------------------------------------- save

    private YamlConfiguration serialize(Island is) {
        YamlConfiguration y = new YamlConfiguration();
        y.set("owner-name", is.ownerName());
        y.set("grid-x", is.gridX());
        y.set("grid-z", is.gridZ());
        y.set("mob-spawning", is.parent() != null ? true : is.mobSpawning());
        if (is.dragonKilled()) y.set("dragon-killed", true);
        if (is.hasRefY()) y.set("ref-y", is.refY());
        if (is.hasSource()) {
            y.set("source-x", is.srcX());
            y.set("source-z", is.srcZ());
        }
        y.set("created", is.created());

        List<String> unlocked = new ArrayList<>();
        for (long key : is.unlockedKeys()) unlocked.add(ChunkPos.fromKey(key).serialize());
        y.set("unlocked", unlocked);

        for (Map.Entry<Long, Map<Material, Integer>> e : is.allProgress().entrySet()) {
            String chunk = ChunkPos.fromKey(e.getKey()).serialize();
            for (Map.Entry<Material, Integer> p : e.getValue().entrySet()) {
                if (p.getValue() > 0) y.set("progress." + chunk + "." + p.getKey().name(), p.getValue());
            }
        }

        for (Map.Entry<Long, Map<Material, Integer>> e : is.allResources().entrySet()) {
            String chunk = ChunkPos.fromKey(e.getKey()).serialize();
            boolean any = false;
            for (Map.Entry<Material, Integer> r : e.getValue().entrySet()) {
                if (r.getValue() <= 0) continue;
                y.set("resources." + chunk + "." + r.getKey().name(), r.getValue());
                any = true;
            }
            if (!any) y.set("resources." + chunk + "." + Material.AIR.name(), 0); // značka "chunk už byl skenován"
        }

        for (Map.Entry<Long, Map<Material, Integer>> e : is.allRequired().entrySet()) {
            String chunk = ChunkPos.fromKey(e.getKey()).serialize();
            for (Map.Entry<Material, Integer> r : e.getValue().entrySet()) {
                if (r.getValue() > 0) y.set("required." + chunk + "." + r.getKey().name(), r.getValue());
            }
        }

        for (Map.Entry<UUID, Integer> e : is.contributions().entrySet()) {
            if (e.getValue() > 0) y.set("contributions." + e.getKey(), e.getValue());
        }
        for (Map.Entry<Long, Map<UUID, Integer>> e : is.allContributors().entrySet()) {
            String chunk = ChunkPos.fromKey(e.getKey()).serialize();
            for (Map.Entry<UUID, Integer> c : e.getValue().entrySet()) {
                if (c.getValue() > 0) y.set("contributors." + chunk + "." + c.getKey(), c.getValue());
            }
        }

        // členy drží ostrov v overworldu, Nether a End je jen odkazují
        for (Map.Entry<UUID, Island.Member> e : (is.parent() != null ? java.util.Map.<UUID, Island.Member>of()
                : is.members()).entrySet()) {
            String base = "members." + e.getKey();
            y.set(base + ".name", e.getValue().name());
            List<String> perms = new ArrayList<>();
            for (IslandPermission p : e.getValue().permissions()) perms.add(p.name());
            y.set(base + ".permissions", perms);
        }
        return y;
    }

    private File islandFile(Dimension dim, UUID owner) {
        return new File(islandsDirs.get(dim), owner + ".yml");
    }

    public void saveIsland(Island island) {
        YamlConfiguration y = serialize(island);
        File file = islandFile(island.dimension(), island.owner());
        if (!plugin.isEnabled()) {
            write(y, file);
            return;
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> write(y, file));
    }

    public void saveState(Dimension dim, int nextSlot, int spacing, Map<UUID, UUID> active) {
        File stateFile = stateFiles.get(dim);
        YamlConfiguration y = new YamlConfiguration();
        y.set("next-slot", nextSlot);
        y.set("spacing", spacing);
        for (Map.Entry<UUID, UUID> e : active.entrySet()) {
            y.set("active." + e.getKey(), e.getValue().toString());
        }
        if (!plugin.isEnabled()) {
            write(y, stateFile);
            return;
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> write(y, stateFile));
    }

    /** Majitelé smazaných ostrovů, které je potřeba teleportovat po připojení. */
    public Set<UUID> loadPendingDelete() {
        Set<UUID> out = new HashSet<>();
        YamlConfiguration y = YamlConfiguration.loadConfiguration(pendingFile);
        for (String s : y.getStringList("players")) {
            try {
                out.add(UUID.fromString(s));
            } catch (IllegalArgumentException ignored) {
                // poškozený záznam
            }
        }
        return out;
    }

    public void savePendingDelete(Set<UUID> players) {
        YamlConfiguration y = new YamlConfiguration();
        List<String> list = new ArrayList<>();
        for (UUID id : players) list.add(id.toString());
        y.set("players", list);
        if (!plugin.isEnabled()) {
            write(y, pendingFile);
            return;
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> write(y, pendingFile));
    }

    // ---------------------------------------------------------------- zálohy

    /**
     * Uloží kopii ostrova (aktuální stav z paměti) do data/backups. Zapisuje se hned (synchronně),
     * protože ostrov se vzápětí maže / resetuje. Vrací soubor zálohy, nebo null (vypnuto / chyba).
     */
    public File backupIsland(Island island, String reason) {
        if (!plugin.settings().backupEnabled) return null;
        try {
            YamlConfiguration y = serialize(island);
            y.set("owner", island.owner().toString());
            y.set("dimension", island.dimension().id());
            y.set("backup-reason", reason);
            y.set("backup-time", System.currentTimeMillis());
            String name = island.ownerName().replaceAll("[^A-Za-z0-9_]", "_");
            String stamp = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss").format(new java.util.Date());
            String tag = island.dimension() == Dimension.OVERWORLD ? "" : island.dimension().id() + "-";
            String prefix = name + "_" + island.owner().toString().substring(0, 8) + "_" + stamp + "_" + tag + reason;
            File file = new File(backupsDir, prefix + ".yml");
            for (int i = 2; file.exists(); i++) file = new File(backupsDir, prefix + "-" + i + ".yml");
            write(y, file);
            plugin.getLogger().info("Záloha ostrova " + island.ownerName() + ": " + file.getName());
            pruneBackups(island.owner(), tag);
            return file;
        } catch (RuntimeException ex) {
            plugin.getLogger().log(Level.SEVERE, "Nelze zálohovat ostrov " + island.ownerName(), ex);
            return null;
        }
    }

    private void pruneBackups(UUID owner, String tag) {
        int keep = plugin.settings().backupKeep;
        if (keep <= 0) return;
        String marker = "_" + owner.toString().substring(0, 8) + "_";
        List<File> mine = new ArrayList<>();
        for (File f : listBackups(null)) {
            String n = f.getName();
            if (!n.contains(marker)) continue;
            boolean nether = n.contains("_nether-");
            boolean end = n.contains("_end-");
            boolean same = tag.isEmpty() ? (!nether && !end) : n.contains("_" + tag);
            if (same) mine.add(f);
        }
        for (int i = keep; i < mine.size(); i++) { // seznam je od nejnovější
            if (!mine.get(i).delete()) plugin.getLogger().warning("Nelze smazat starou zálohu " + mine.get(i).getName());
        }
    }

    /** Zálohy od nejnovější; filter (část jména souboru, bez ohledu na velikost písmen) může být null. */
    public List<File> listBackups(String filter) {
        List<File> out = new ArrayList<>();
        File[] files = backupsDir.listFiles((dir, name) -> name.endsWith(".yml"));
        if (files == null) return out;
        String f = filter == null ? null : filter.toLowerCase(java.util.Locale.ROOT);
        for (File file : files) {
            if (f == null || file.getName().toLowerCase(java.util.Locale.ROOT).contains(f)) out.add(file);
        }
        out.sort((a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        return out;
    }

    /** Najde zálohu podle jména (s .yml nebo bez). Jen bezpečné znaky, nic mimo složku backups. */
    public File findBackup(String name) {
        if (name == null || !name.matches("[A-Za-z0-9_.\\-]+")) return null;
        File f = new File(backupsDir, name.endsWith(".yml") ? name : name + ".yml");
        return f.isFile() ? f : null;
    }

    /** Načte ostrov ze zálohy, nebo null když je soubor poškozený. */
    public Island readBackup(File file) {
        try {
            YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
            String owner = y.getString("owner");
            if (owner == null) return null;
            return read(y, UUID.fromString(owner), file, Dimension.parse(y.getString("dimension", "overworld")));
        } catch (RuntimeException ex) {
            plugin.getLogger().log(Level.WARNING, "Nelze načíst zálohu " + file.getName(), ex);
            return null;
        }
    }

    public void deleteIsland(Dimension dim, UUID owner) {
        File f = islandFile(dim, owner);
        synchronized (writeLock) {
            if (f.exists() && !f.delete()) {
                plugin.getLogger().warning("Nelze smazat " + f.getName());
            }
        }
    }

    private void write(YamlConfiguration y, File file) {
        synchronized (writeLock) {
            try {
                File parent = file.getParentFile();
                if (parent != null && !parent.exists()) parent.mkdirs();
                y.save(file);
            } catch (IOException ex) {
                plugin.getLogger().log(Level.SEVERE, "Nelze uložit " + file.getName(), ex);
            }
        }
    }
}
