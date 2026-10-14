package dev.chunklock.manager;

import dev.chunklock.ChunkLockPlugin;
import dev.chunklock.model.ChunkPos;
import dev.chunklock.model.Island;
import dev.chunklock.model.IslandPermission;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/** Ukládání ostrovů do plugins/ChunkLock/data (jeden YAML na ostrov). */
public final class Storage {

    public record State(int nextSlot, int spacing, Map<UUID, UUID> active) {}

    private final ChunkLockPlugin plugin;
    private final File islandsDir;
    private final File stateFile;
    private final Object writeLock = new Object();

    public Storage(ChunkLockPlugin plugin) {
        this.plugin = plugin;
        File dataDir = new File(plugin.getDataFolder(), "data");
        this.islandsDir = new File(dataDir, "islands");
        this.stateFile = new File(dataDir, "state.yml");
        if (!islandsDir.exists() && !islandsDir.mkdirs()) {
            plugin.getLogger().severe("Nelze vytvořit složku " + islandsDir);
        }
    }

    // ---------------------------------------------------------------- load

    public List<Island> loadIslands() {
        List<Island> out = new ArrayList<>();
        File[] files = islandsDir.listFiles((dir, name) -> name.endsWith(".yml"));
        if (files == null) return out;
        for (File f : files) {
            try {
                out.add(read(f));
            } catch (Exception ex) {
                plugin.getLogger().log(Level.SEVERE, "Nelze načíst ostrov " + f.getName(), ex);
            }
        }
        return out;
    }

    private Island read(File file) {
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        UUID owner = UUID.fromString(file.getName().substring(0, file.getName().length() - 4));
        Island island = new Island(owner, y.getString("owner-name", "?"), y.getInt("grid-x"), y.getInt("grid-z"));

        for (String s : y.getStringList("unlocked")) {
            island.unlock(ChunkPos.parse(s));
        }
        if (island.unlockedCount() == 0) island.unlock(new ChunkPos(0, 0));
        island.setMobSpawning(y.getBoolean("mob-spawning", true));

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

    public State loadState(int defaultSpacing) {
        YamlConfiguration y = YamlConfiguration.loadConfiguration(stateFile);
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
        y.set("mob-spawning", is.mobSpawning());

        List<String> unlocked = new ArrayList<>();
        for (long key : is.unlockedKeys()) unlocked.add(ChunkPos.fromKey(key).serialize());
        y.set("unlocked", unlocked);

        for (Map.Entry<Long, Map<Material, Integer>> e : is.allProgress().entrySet()) {
            String chunk = ChunkPos.fromKey(e.getKey()).serialize();
            for (Map.Entry<Material, Integer> p : e.getValue().entrySet()) {
                if (p.getValue() > 0) y.set("progress." + chunk + "." + p.getKey().name(), p.getValue());
            }
        }

        for (Map.Entry<UUID, Island.Member> e : is.members().entrySet()) {
            String base = "members." + e.getKey();
            y.set(base + ".name", e.getValue().name());
            List<String> perms = new ArrayList<>();
            for (IslandPermission p : e.getValue().permissions()) perms.add(p.name());
            y.set(base + ".permissions", perms);
        }
        return y;
    }

    private File islandFile(UUID owner) {
        return new File(islandsDir, owner + ".yml");
    }

    public void saveIsland(Island island) {
        YamlConfiguration y = serialize(island);
        File file = islandFile(island.owner());
        if (!plugin.isEnabled()) {
            write(y, file);
            return;
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> write(y, file));
    }

    public void saveState(int nextSlot, int spacing, Map<UUID, UUID> active) {
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

    public void deleteIsland(UUID owner) {
        File f = islandFile(owner);
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
