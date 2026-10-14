package dev.chunklock.manager;

import dev.chunklock.ChunkLockPlugin;
import dev.chunklock.config.Messages;
import dev.chunklock.config.Settings;
import dev.chunklock.model.ChunkPos;
import dev.chunklock.model.Island;
import dev.chunklock.util.VoidGenerator;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.TreeType;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/** Správa všech ostrovů, jejich umístění ve světě, generování chunků a teleportů. */
public final class IslandManager {

    public record Plot(Island island, ChunkPos rel) {}

    public static final int[][] DIRS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    private final ChunkLockPlugin plugin;
    private final Map<UUID, Island> islands = new HashMap<>();
    private final Map<Long, Island> bySlot = new HashMap<>();
    private final Map<UUID, UUID> active = new HashMap<>();
    private final Random random = new Random();
    /** Majitelé smazaných ostrovů, kteří byli offline - při připojení je teleportuje na delete.location. */
    private final Set<UUID> pendingDelete = new HashSet<>();
    private int nextSlot;
    private int spacing;
    private World world;

    public IslandManager(ChunkLockPlugin plugin) {
        this.plugin = plugin;
    }

    // ---------------------------------------------------------------- init

    /** @return false pokud se nepodařilo získat chunk svět */
    public boolean init() {
        Settings s = plugin.settings();
        // vzdálenost ostrovů v chuncích: min. rozměr ostrova + mezera, nebo distance-blocks (např. 2000 bloků)
        int configured = Math.max(2 * s.maxRadius + 1 + s.gap, (int) Math.ceil(s.distanceBlocks / 16.0));
        Storage.State state = plugin.storage().loadState(configured);
        nextSlot = state.nextSlot();
        active.putAll(state.active());
        pendingDelete.addAll(plugin.storage().loadPendingDelete());

        for (Island island : plugin.storage().loadIslands()) {
            islands.put(island.owner(), island);
            bySlot.put(slotKey(island.gridX(), island.gridZ()), island);
        }
        // Rozestup se dá změnit jen dokud neexistuje žádný ostrov, jinak by se rozbily pozice stávajících.
        spacing = islands.isEmpty() ? Math.max(4, configured) : Math.max(4, state.spacing());
        saveState();
        return resolveWorld();
    }

    private boolean resolveWorld() {
        Settings s = plugin.settings();
        World w = Bukkit.getWorld(s.worldName);
        if (w == null && s.createWorld) {
            w = new WorldCreator(s.worldName)
                    .generator(new VoidGenerator())
                    .environment(World.Environment.NORMAL)
                    .type(WorldType.NORMAL)
                    .generateStructures(false)
                    .createWorld();
        }
        if (w == null) {
            plugin.getLogger().severe("Svět '" + s.worldName + "' neexistuje a create-if-missing je vypnuté.");
            return false;
        }
        world = w;
        return true;
    }

    public World world() {
        return world;
    }

    public boolean isChunkWorld(World w) {
        return world != null && world.equals(w);
    }

    public Collection<Island> all() {
        return islands.values();
    }

    public void saveState() {
        plugin.storage().saveState(nextSlot, spacing, active);
    }

    public void saveAll() {
        for (Island island : islands.values()) plugin.storage().saveIsland(island);
        saveState();
    }

    // ---------------------------------------------------------------- lookup

    public Island byOwner(UUID uuid) {
        return islands.get(uuid);
    }

    public Island byOwnerName(String name) {
        for (Island i : islands.values()) {
            if (i.ownerName().equalsIgnoreCase(name)) return i;
        }
        return null;
    }

    /** Ostrov, na který se hráč teleportuje přes /chunk (vlastní, nebo ten, kam byl pozván). */
    public Island activeIsland(UUID player) {
        UUID owner = active.get(player);
        if (owner != null) {
            Island i = islands.get(owner);
            if (i != null && i.isMember(player)) return i;
        }
        return islands.get(player);
    }

    public void setActive(UUID player, UUID owner) {
        if (player.equals(owner)) {
            active.remove(player);
        } else {
            active.put(player, owner);
        }
        saveState();
    }

    // ---------------------------------------------------------------- geometrie

    private static long slotKey(int gx, int gz) {
        return new ChunkPos(gx, gz).key();
    }

    public int radius() {
        return Math.max(1, Math.min(plugin.settings().maxRadius, (spacing - 2) / 2));
    }

    public int maxChunks() {
        int side = 2 * radius() + 1;
        return side * side;
    }

    public int centerChunkX(Island is) {
        return is.gridX() * spacing;
    }

    public int centerChunkZ(Island is) {
        return is.gridZ() * spacing;
    }

    public Plot plotAt(Location loc) {
        if (loc.getWorld() == null || !isChunkWorld(loc.getWorld())) return null;
        return plotAtChunk(loc.getBlockX() >> 4, loc.getBlockZ() >> 4);
    }

    public Plot plotAtChunk(int cx, int cz) {
        int gx = Math.floorDiv(cx + spacing / 2, spacing);
        int gz = Math.floorDiv(cz + spacing / 2, spacing);
        Island island = bySlot.get(slotKey(gx, gz));
        if (island == null) return null;
        return new Plot(island, new ChunkPos(cx - gx * spacing, cz - gz * spacing));
    }

    public Location spawnLocation(Island is) {
        double x = (centerChunkX(is) << 4) + 8.5;
        double z = (centerChunkZ(is) << 4) + 8.5;
        return new Location(world, x, plugin.settings().surfaceY + 1, z);
    }

    /** Spirála od středu: 0 -> (0,0), pak prstenec 1, prstenec 2 ... */
    private static int[] slot(int n) {
        if (n == 0) return new int[]{0, 0};
        int ring = 1;
        int count = 1;
        while (true) {
            int ringSize = 8 * ring;
            if (n < count + ringSize) {
                int idx = n - count;
                for (int x = -ring; x <= ring; x++) {
                    for (int z = -ring; z <= ring; z++) {
                        if (Math.max(Math.abs(x), Math.abs(z)) == ring) {
                            if (idx-- == 0) return new int[]{x, z};
                        }
                    }
                }
            }
            count += ringSize;
            ring++;
        }
    }

    // ---------------------------------------------------------------- odemykání

    public boolean canUnlock(Island is, ChunkPos rel) {
        if (rel.distance() > radius() || is.isUnlocked(rel)) return false;
        for (int[] d : DIRS) {
            if (is.isUnlocked(rel.offset(d[0], d[1]))) return true;
        }
        return false;
    }

    /** Zamčené chunky sousedící s odemčenými (tam se zobrazují cedulky). */
    public Set<ChunkPos> lockedFrontier(Island is) {
        Set<ChunkPos> out = new LinkedHashSet<>();
        for (long key : is.unlockedKeys()) {
            ChunkPos u = ChunkPos.fromKey(key);
            for (int[] d : DIRS) {
                ChunkPos n = u.offset(d[0], d[1]);
                if (!is.isUnlocked(n) && n.distance() <= radius()) out.add(n);
            }
        }
        return out;
    }

    public void unlockChunk(Island is, ChunkPos rel) {
        is.unlock(rel);
        is.clearProgress(rel);
        generateChunk(is, rel, false);
        plugin.storage().saveIsland(is);
        plugin.panels().spawn(is);
    }

    // ---------------------------------------------------------------- vytvoření

    public Island createIsland(Player owner) {
        int[] s = slot(nextSlot++);
        Island is = new Island(owner.getUniqueId(), owner.getName(), s[0], s[1]);
        islands.put(is.owner(), is);
        bySlot.put(slotKey(is.gridX(), is.gridZ()), is);
        is.unlock(new ChunkPos(0, 0));
        generateChunk(is, new ChunkPos(0, 0), true);
        plugin.storage().saveIsland(is);
        saveState();
        return is;
    }

    public void generateChunk(Island is, ChunkPos rel, boolean start) {
        Settings s = plugin.settings();
        Chunk chunk = world.getChunkAt(centerChunkX(is) + rel.x(), centerChunkZ(is) + rel.z());
        int baseX = chunk.getX() << 4;
        int baseZ = chunk.getZ() << 4;

        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                chunk.getBlock(x, s.surfaceY, z).setType(s.topBlock, false);
                for (int d = 1; d <= s.fillerDepth; d++) {
                    chunk.getBlock(x, s.surfaceY - d, z).setType(s.fillerBlock, false);
                }
                for (int d = s.fillerDepth + 1; d <= s.fillerDepth + s.stoneDepth; d++) {
                    chunk.getBlock(x, s.surfaceY - d, z).setType(s.stoneBlock, false);
                }
                if (s.bedrockBottom) {
                    chunk.getBlock(x, s.surfaceY - s.fillerDepth - s.stoneDepth - 1, z)
                            .setType(Material.BEDROCK, false);
                }
            }
        }

        placeOres(chunk, start ? s.startOres : s.unlockedOres);

        Settings.TreeSettings ts = start ? s.startTrees : s.unlockedTrees;
        int count = rollTrees(ts);
        List<int[]> placed = new ArrayList<>();
        int tries = 0;
        while (placed.size() < count && tries++ < 300) {
            int x = 3 + random.nextInt(10);
            int z = 3 + random.nextInt(10);
            if (start && Math.abs(x - 8) < 3 && Math.abs(z - 8) < 3) continue; // volné místo kolem spawnu
            boolean tooClose = false;
            for (int[] p : placed) {
                if (Math.max(Math.abs(p[0] - x), Math.abs(p[1] - z)) < ts.minDistance()) {
                    tooClose = true;
                    break;
                }
            }
            if (tooClose) continue;
            TreeType type = ts.types().get(random.nextInt(ts.types().size()));
            Location loc = new Location(world, baseX + x, s.surfaceY + 1, baseZ + z);
            if (world.generateTree(loc, type)) placed.add(new int[]{x, z});
        }
    }

    /** Rozmístí rudy do kamenné vrstvy chunku podle configu (počet bloků, šance, žíly, hloubka). */
    private void placeOres(Chunk chunk, Settings.OreSettings ores) {
        if (!ores.enabled()) return;
        Settings s = plugin.settings();
        int stoneTop = s.fillerDepth + 1;
        int stoneBottom = s.fillerDepth + s.stoneDepth;
        for (Settings.Ore ore : ores.list()) {
            if (ore.amount() <= 0 || random.nextDouble() * 100.0 >= ore.chance()) continue;
            boolean full = plugin.getConfig().getBoolean("island.terrain.ores-full-depth", false);
            int min = full ? stoneTop : Math.max(stoneTop, ore.minDepth());
            int max = full ? stoneBottom : Math.min(stoneBottom, ore.maxDepth());
            if (min > max) continue;
            int remaining = ore.amount();
            int guard = ore.amount() * 20 + 50;
            while (remaining > 0 && guard-- > 0) {
                int x = random.nextInt(16);
                int z = random.nextInt(16);
                int depth = min + random.nextInt(max - min + 1);
                remaining -= placeVein(chunk, ore.material(), x, z, depth, Math.min(remaining, ore.vein()), min, max);
            }
        }
    }

    /** Žíla = náhodná procházka od počátečního bodu, mění jen kámen. */
    private int placeVein(Chunk chunk, Material ore, int x, int z, int depth, int size, int minDepth, int maxDepth) {
        Settings s = plugin.settings();
        int placed = 0;
        for (int step = 0; step < size * 6 && placed < size; step++) {
            Block b = chunk.getBlock(x, s.surfaceY - depth, z);
            if (b.getType() == s.stoneBlock) {
                b.setType(ore, false);
                placed++;
            }
            switch (random.nextInt(3)) {
                case 0 -> x = Math.max(0, Math.min(15, x + (random.nextBoolean() ? 1 : -1)));
                case 1 -> z = Math.max(0, Math.min(15, z + (random.nextBoolean() ? 1 : -1)));
                default -> depth = Math.max(minDepth, Math.min(maxDepth, depth + (random.nextBoolean() ? 1 : -1)));
            }
        }
        return placed;
    }

    private int rollTrees(Settings.TreeSettings t) {
        if (!t.enabled() || t.chances().isEmpty()) return 0;
        if (t.highestFirst()) {
            for (Map.Entry<Integer, Double> e : t.chances().descendingMap().entrySet()) {
                if (random.nextDouble() * 100.0 < e.getValue()) return e.getKey();
            }
            return 0;
        }
        int count = 0;
        for (Map.Entry<Integer, Double> e : t.chances().entrySet()) {
            if (random.nextDouble() * 100.0 < e.getValue()) {
                count = e.getKey();
            } else {
                break;
            }
        }
        return count;
    }

    // ---------------------------------------------------------------- teleport

    public void teleport(Player player, Island island, Runnable after) {
        player.teleportAsync(spawnLocation(island)).thenAccept(ok ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (ok) plugin.panels().ensure(island);
                    if (after != null) after.run();
                }));
    }

    /** Pošle hráče pryč z ostrova - na vlastní ostrov (pokud není "avoid"), jinak na spawn hlavního světa. */
    public void sendToSafe(Player player, Island avoid) {
        Island own = byOwner(player.getUniqueId());
        if (own != null && own != avoid) {
            teleport(player, own, null);
        } else {
            player.teleportAsync(Bukkit.getWorlds().get(0).getSpawnLocation());
        }
    }

    public void onMemberRemoved(Island island, UUID member) {
        if (island.owner().equals(active.get(member))) {
            active.remove(member);
            saveState();
        }
        Player online = Bukkit.getPlayer(member);
        if (online != null) {
            Plot plot = plotAt(online.getLocation());
            if (plot != null && plot.island() == island) {
                sendToSafe(online, island);
            }
        }
    }

    // ---------------------------------------------------------------- reset / smazání

    private List<Player> playersInside(Island island) {
        List<Player> out = new ArrayList<>();
        for (Player p : world.getPlayers()) {
            Plot plot = plotAt(p.getLocation());
            if (plot != null && plot.island() == island) out.add(p);
        }
        return out;
    }

    /** Kam poslat hráče: svět + souřadnice z configu (sekce "reset" nebo "delete", podklíč location). */
    private Location configLocation(String section) {
        FileConfiguration cfg = plugin.getConfig();
        String path = section + ".location";
        String worldName = cfg.getString(path + ".world", "").trim();
        World w = worldName.isEmpty() ? null : Bukkit.getWorld(worldName);
        if (w == null) {
            if (!worldName.isEmpty()) {
                plugin.getLogger().warning(path + ".world '" + worldName + "' neexistuje, používám spawn hlavního světa.");
            }
            return Bukkit.getWorlds().get(0).getSpawnLocation();
        }
        return new Location(w,
                cfg.getDouble(path + ".x", w.getSpawnLocation().getX()),
                cfg.getDouble(path + ".y", w.getSpawnLocation().getY()),
                cfg.getDouble(path + ".z", w.getSpawnLocation().getZ()),
                (float) cfg.getDouble(path + ".yaw", 0),
                (float) cfg.getDouble(path + ".pitch", 0));
    }

    /** Pošle hráče na místo pro reset a po teleportu spustí příkazy z configu (reset.commands). */
    private void sendToReset(Player player) {
        sendTo(player, "reset");
    }

    /** Pošle hráče na místo po smazání ostrova (delete.location) a spustí delete.commands. */
    private void sendToDelete(Player player) {
        sendTo(player, "delete");
    }

    private void sendTo(Player player, String section) {
        player.teleportAsync(configLocation(section)).thenAccept(ok ->
                Bukkit.getScheduler().runTask(plugin, () -> runCommands(player, section + ".commands")));
    }

    /** Volá se při připojení hráče: pokud mu byl ostrov smazán v offline, teleportuje ho. */
    public boolean applyPendingDelete(Player player) {
        if (!pendingDelete.remove(player.getUniqueId())) return false;
        plugin.storage().savePendingDelete(pendingDelete);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) sendToDelete(player);
        }, 5L);
        return true;
    }

    /** Příkazy: "[console] cmd" (výchozí) nebo "[player] cmd"; %player% = jméno hráče. */
    private void runCommands(Player player, String path) {
        if (!player.isOnline()) return;
        for (String raw : plugin.getConfig().getStringList(path)) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            boolean asPlayer = false;
            if (line.regionMatches(true, 0, "[player]", 0, 8)) {
                asPlayer = true;
                line = line.substring(8).trim();
            } else if (line.regionMatches(true, 0, "[console]", 0, 9)) {
                line = line.substring(9).trim();
            }
            if (line.startsWith("/")) line = line.substring(1);
            line = line.replace("%player%", player.getName());
            if (line.isEmpty()) continue;
            if (asPlayer) player.performCommand(line);
            else Bukkit.dispatchCommand(Bukkit.getConsoleSender(), line);
        }
    }

    public void resetIsland(Island island, Player initiator) {
        for (Player p : playersInside(island)) sendToReset(p);
        plugin.panels().remove(island.owner());
        Bukkit.getScheduler().runTaskLater(plugin, () -> clearIsland(island, () -> {
            island.reset();
            generateChunk(island, new ChunkPos(0, 0), true);
            plugin.storage().saveIsland(island);
            if (initiator.isOnline()) {
                plugin.messages().send(initiator, "reset.done");
                if (plugin.getConfig().getBoolean("reset.teleport-back", true)) {
                    teleport(initiator, island, null);
                }
            }
        }), 20L);
    }

    public void deleteIsland(Island island) {
        // hráči stojící na ostrově + majitel (ať je kdekoliv) se teleportují na delete.location
        Set<UUID> targets = new LinkedHashSet<>();
        for (Player p : playersInside(island)) targets.add(p.getUniqueId());
        targets.add(island.owner());
        boolean pendingChanged = false;
        for (UUID id : targets) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                sendToDelete(p);
            } else if (id.equals(island.owner())) {
                pendingDelete.add(id); // majitel je offline - teleport proběhne při připojení
                pendingChanged = true;
            }
        }
        if (pendingChanged) plugin.storage().savePendingDelete(pendingDelete);
        plugin.panels().remove(island.owner());
        islands.remove(island.owner());
        bySlot.remove(slotKey(island.gridX(), island.gridZ()));
        active.values().removeIf(owner -> owner.equals(island.owner()));
        plugin.storage().deleteIsland(island.owner());
        saveState();
        Bukkit.getScheduler().runTaskLater(plugin, () -> clearIsland(island, null), 20L);
    }

    /** Vymaže bloky odemčených chunků, jeden chunk za tick. */
    private void clearIsland(Island island, Runnable done) {
        List<ChunkPos> chunks = new ArrayList<>();
        for (long key : island.unlockedKeys()) chunks.add(ChunkPos.fromKey(key));
        final int cx = centerChunkX(island);
        final int cz = centerChunkZ(island);
        new BukkitRunnable() {
            int index = 0;

            @Override
            public void run() {
                if (index >= chunks.size()) {
                    cancel();
                    if (done != null) done.run();
                    return;
                }
                ChunkPos rel = chunks.get(index++);
                clearChunk(world.getChunkAt(cx + rel.x(), cz + rel.z()));
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    private void clearChunk(Chunk chunk) {
        Settings s = plugin.settings();
        for (Entity e : chunk.getEntities()) {
            if (!(e instanceof Player)) e.remove();
        }
        int minY = Math.max(world.getMinHeight(), s.surfaceY - s.fillerDepth - s.stoneDepth - 3);
        int maxY = Math.min(world.getMaxHeight() - 1, s.surfaceY + 64);
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = minY; y <= maxY; y++) {
                    Block b = chunk.getBlock(x, y, z);
                    if (!b.getType().isAir()) b.setType(Material.AIR, false);
                }
            }
        }
    }

    public Messages messages() {
        return plugin.messages();
    }
}
