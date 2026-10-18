package dev.chunklock.manager;

import dev.chunklock.ChunkLockPlugin;
import dev.chunklock.config.Messages;
import dev.chunklock.config.Settings;
import dev.chunklock.model.ChunkPos;
import dev.chunklock.model.Dimension;
import dev.chunklock.model.Island;
import dev.chunklock.util.VoidGenerator;
import org.bukkit.Bukkit;
import org.bukkit.HeightMap;
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
    private final Dimension dim;
    private final Map<UUID, Island> islands = new HashMap<>();
    private final Map<Long, Island> bySlot = new HashMap<>();
    private final Map<UUID, UUID> active = new HashMap<>();
    private final Random random = new Random();
    /** Majitelé smazaných ostrovů, kteří byli offline - při připojení je teleportuje na delete.location. */
    private final Set<UUID> pendingDelete = new HashSet<>();
    /** Majitelé ostrovů, kterým se po smazání ještě uklízí bloky (do té doby nejde obnovit záloha). */
    private final Set<UUID> clearing = new HashSet<>();
    private int nextSlot;
    private int spacing;
    private World world;

    private TerrainCopier terrain;
    /** Ostrovy, kterým se zrovna kopíruje terén (počet běžících generování). */
    private final Map<UUID, Integer> generating = new HashMap<>();
    private final Map<UUID, List<Runnable>> waiting = new HashMap<>();

    public IslandManager(ChunkLockPlugin plugin, Dimension dim) {
        this.plugin = plugin;
        this.dim = dim;
    }

    public Dimension dimension() {
        return dim;
    }

    // ---------------------------------------------------------------- init

    /** @return false pokud se nepodařilo získat chunk svět */
    public boolean init() {
        Settings s = plugin.settings();
        // vzdálenost ostrovů v chuncích: min. rozměr ostrova + mezera, nebo distance-blocks (např. 2000 bloků)
        int configured = Math.max(2 * s.dimension(dim).maxRadius() + 1 + s.gap, (int) Math.ceil(s.distanceBlocks / 16.0));
        Storage.State state = plugin.storage().loadState(dim, configured);
        nextSlot = state.nextSlot();
        // End: slot 0 je ve středu světa, kde běží vanilla souboj s drakem - ostrovy hráčů začínají mimo něj
        if (dim == Dimension.END && nextSlot < 1) nextSlot = 1;
        active.putAll(state.active());
        if (dim == Dimension.OVERWORLD) pendingDelete.addAll(plugin.storage().loadPendingDelete());

        for (Island island : plugin.storage().loadIslands(dim)) {
            if (dim != Dimension.OVERWORLD) { // ostrov v Netheru / Endu patří k ostrovu v overworldu (sdílí členy)
                Island parent = plugin.islands().byOwner(island.owner());
                if (parent == null) {
                    plugin.getLogger().warning("Ostrov " + island.ownerName() + " (" + dim.id()
                            + ") nemá ostrov v overworldu, přeskakuji.");
                    continue;
                }
                island.setParent(parent);
            }
            islands.put(island.owner(), island);
            bySlot.put(slotKey(island.gridX(), island.gridZ()), island);
        }
        // Rozestup se dá změnit jen dokud neexistuje žádný ostrov, jinak by se rozbily pozice stávajících.
        spacing = islands.isEmpty() ? Math.max(4, configured) : Math.max(4, state.spacing());
        saveState();
        if (!resolveWorld()) return false;
        if (dim != Dimension.OVERWORLD || s.worldGeneration) {
            TerrainCopier copier = new TerrainCopier(plugin, dim);
            if (copier.init()) {
                terrain = copier;
            } else {
                plugin.getLogger().warning("Generování z normálního světa (" + dim.id()
                        + ") se nepodařilo zapnout - používám plochý ostrov.");
            }
        }
        return true;
    }

    /** Je zapnuté generování chunků z normálního světa? */
    public boolean worldMode() {
        return terrain != null;
    }

    /** Pod jakou výškou se hráč vrací zpět na ostrov (u ostrovů z normálního světa je to pod spodkem světa). */
    public int rescueY(Location loc) {
        if (dim != Dimension.OVERWORLD) return world.getMinHeight() - 8; // Nether / End: pod spodkem světa
        Plot plot = plotAt(loc);
        if (plot != null && plot.island().hasSource()) return plugin.settings().voidRescueY;
        // ploché ostrovy: kousek pod spodkem chunku
        Settings s = plugin.settings();
        return Math.max(world.getMinHeight(), s.surfaceY - s.fillerDepth - s.stoneDepth - 8);
    }

    private void beginGeneration(Island is) {
        generating.merge(is.owner(), 1, Integer::sum);
    }

    private void endGeneration(Island is) {
        Integer left = generating.computeIfPresent(is.owner(), (k, v) -> v <= 1 ? null : v - 1);
        if (left != null) return;
        List<Runnable> w = waiting.remove(is.owner());
        if (w != null) for (Runnable r : w) r.run();
    }

    private boolean resolveWorld() {
        Settings s = plugin.settings();
        String name = s.dimension(dim).world();
        World w = Bukkit.getWorld(name);
        if (w == null && (s.createWorld || dim != Dimension.OVERWORLD)) {
            w = new WorldCreator(name)
                    .generator(new VoidGenerator())
                    .environment(dim.environment())
                    .type(WorldType.NORMAL)
                    .generateStructures(false)
                    .createWorld();
        }
        if (w == null) {
            plugin.getLogger().severe("Svět '" + name + "' neexistuje a create-if-missing je vypnuté.");
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
        plugin.storage().saveState(dim, nextSlot, spacing, active);
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
        return Math.max(1, Math.min(plugin.settings().dimension(dim).maxRadius(), (spacing - 2) / 2));
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
        if (dim == Dimension.NETHER) return netherSpawn(is, x, z);
        if (dim == Dimension.END) { // kousek od výstupního portálu uprostřed chunku
            double ez = z + 6;
            return new Location(world, x, world.getHighestBlockYAt((int) Math.floor(x), (int) Math.floor(ez),
                    HeightMap.MOTION_BLOCKING_NO_LEAVES) + 1, ez);
        }
        double y = plugin.settings().surfaceY + 1;
        if (is.hasSource()) {
            y = world.getHighestBlockYAt((int) Math.floor(x), (int) Math.floor(z),
                    HeightMap.MOTION_BLOCKING_NO_LEAVES) + 1;
        }
        return new Location(world, x, y, z);
    }

    /** Nether: první místo k stání poblíž středu startovního chunku (nahoře je strop z bedrocku, dole láva). */
    private Location netherSpawn(Island is, double x, double z) {
        int top = is.hasRefY() ? Math.min(120, is.refY() + 24) : 118;
        int bottom = is.hasRefY() ? Math.max(1, is.refY() - 24) : 34;
        for (int r = 0; r <= 6; r += 2) {
            for (int dx = -r; dx <= r; dx += 2) {
                for (int dz = -r; dz <= r; dz += 2) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    int bx = (int) Math.floor(x) + dx;
                    int bz = (int) Math.floor(z) + dz;
                    int y = TerrainCopier.findFloorY(world, bx, bz, top, bottom);
                    if (y != Integer.MIN_VALUE) return new Location(world, bx + 0.5, y, bz + 0.5);
                }
            }
        }
        return new Location(world, x, is.hasRefY() ? is.refY() : 64, z);
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
        is.clearRequired(rel);
        plugin.panels().suspend(is); // cedulky vzniknou až po dokončení chunku (výška terénu)
        beginGeneration(is);
        Runnable finish = () -> {
            scanResources(is, rel); // musí být hotové dřív, než vzniknou cedulky sousedních chunků
            endGeneration(is);
            plugin.storage().saveIsland(is);
            plugin.panels().resume(is);
            plugin.panels().spawn(is);
        };
        try {
            generateChunk(is, rel, false, finish);
        } catch (RuntimeException ex) {
            plugin.getLogger().log(java.util.logging.Level.WARNING, "Generování chunku selhalo", ex);
            finish.run();
        }
    }

    /** Zjistí, co jde v čerstvě vygenerovaném chunku získat (podklad pro požadavky sousedních chunků). */
    private void scanResources(Island is, ChunkPos rel) {
        try {
            plugin.resources().record(is, rel);
        } catch (RuntimeException ex) {
            plugin.getLogger().log(java.util.logging.Level.WARNING, "Sken surovin chunku selhal", ex);
        }
    }

    /** Admin: zamkne odemčený chunk zpět (smaže jeho bloky). Startovní chunk zamknout nejde. */
    public boolean lockChunk(Island is, ChunkPos rel) {
        if ((rel.x() == 0 && rel.z() == 0) || !is.isUnlocked(rel)) return false;
        for (Player p : world.getPlayers()) {
            Plot plot = plotAt(p.getLocation());
            if (plot != null && plot.island() == is && plot.rel().equals(rel)) {
                p.teleportAsync(spawnLocation(is));
            }
        }
        is.lock(rel);
        is.clearProgress(rel);
        is.clearResources(rel);
        is.clearAllRequired(); // sousedé se můžou počítat z jiného chunku
        clearChunk(world.getChunkAt(centerChunkX(is) + rel.x(), centerChunkZ(is) + rel.z()), is.hasSource());
        plugin.storage().saveIsland(is);
        plugin.panels().spawn(is);
        return true;
    }

    // ---------------------------------------------------------------- vytvoření

    public Island createIsland(Player owner) {
        return createIslandInternal(null, owner.getUniqueId(), owner.getName());
    }

    /** Nether / End ostrov hráče, který už má ostrov v overworldu (sdílí s ním členy). */
    public Island createChild(Island parent) {
        return createIslandInternal(parent, parent.owner(), parent.ownerName());
    }

    private Island createIslandInternal(Island parent, UUID ownerId, String ownerName) {
        int[] s = slot(nextSlot++);
        Island is = new Island(dim, parent, ownerId, ownerName, s[0], s[1]);
        islands.put(is.owner(), is);
        bySlot.put(slotKey(is.gridX(), is.gridZ()), is);
        is.unlock(new ChunkPos(0, 0));
        plugin.storage().saveIsland(is);
        saveState();
        beginGeneration(is);
        Runnable finish = () -> {
            scanResources(is, new ChunkPos(0, 0));
            endGeneration(is);
            plugin.storage().saveIsland(is);
        };
        try {
            generateChunk(is, new ChunkPos(0, 0), true, finish);
        } catch (RuntimeException ex) {
            plugin.getLogger().log(java.util.logging.Level.WARNING, "Generování startovního chunku selhalo", ex);
            finish.run();
        }
        return is;
    }

    /**
     * Vygeneruje chunk ostrova. V režimu WORLD zkopíruje terén ze zdrojového světa (asynchronně),
     * jinak vytvoří plochý ostrov. done se zavolá po dokončení.
     */
    public void generateChunk(Island is, ChunkPos rel, boolean start, Runnable done) {
        boolean useWorld = terrain != null && (start || is.hasSource());
        if (!useWorld) {
            if (start) is.clearSource();
            if (dim == Dimension.OVERWORLD) generateFlat(is, rel, start);
            else generateSimplePlatform(is, rel);
            if (start) onStartGenerated(is);
            if (done != null) done.run();
            return;
        }
        if (start) {
            Runnable afterStart = () -> {
                onStartGenerated(is);
                if (done != null) done.run();
            };
            if (dim == Dimension.END) { // hlavní ostrov Endu: střed zdrojového světa
                is.setSource(0, 0);
                copyFromSource(is, rel, afterStart);
                return;
            }
            terrain.findStart(origin -> {
                is.setSource(origin[0], origin[1]);
                copyFromSource(is, rel, afterStart);
            });
        } else {
            copyFromSource(is, rel, done);
        }
    }

    /** Po vygenerování startovního chunku: Nether zjistí podlahu, End postaví podium s portálem. */
    private void onStartGenerated(Island is) {
        try {
            if (dim == Dimension.NETHER) {
                int bx = (centerChunkX(is) << 4) + 8;
                int bz = (centerChunkZ(is) << 4) + 8;
                int y = TerrainCopier.findFloorY(world, bx, bz, 120, 1);
                if (y == Integer.MIN_VALUE) { // ani po kopírování není kam stoupnout - uděláme malou plošinu
                    y = 64;
                    for (int dx = -2; dx <= 2; dx++) {
                        for (int dz = -2; dz <= 2; dz++) {
                            world.getBlockAt(bx + dx, y - 1, bz + dz).setType(Material.NETHERRACK, false);
                            world.getBlockAt(bx + dx, y, bz + dz).setType(Material.AIR, false);
                            world.getBlockAt(bx + dx, y + 1, bz + dz).setType(Material.AIR, false);
                        }
                    }
                }
                is.setRefY(y);
            } else if (dim == Dimension.END) {
                plugin.end().buildPodium(is);
            }
        } catch (RuntimeException ex) {
            plugin.getLogger().log(java.util.logging.Level.WARNING, "Dokončení startovního chunku selhalo", ex);
        }
    }

    /** Záloha, když se nepodaří zapnout kopírování terénu: jednoduchá plošina (Nether: netherrack, End: end stone). */
    private void generateSimplePlatform(Island is, ChunkPos rel) {
        Chunk chunk = world.getChunkAt(centerChunkX(is) + rel.x(), centerChunkZ(is) + rel.z());
        Material m = dim == Dimension.NETHER ? Material.NETHERRACK : Material.END_STONE;
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = 58; y <= 63; y++) chunk.getBlock(x, y, z).setType(m, false);
            }
        }
    }

    private void copyFromSource(Island is, ChunkPos rel, Runnable done) {
        Chunk target = world.getChunkAt(centerChunkX(is) + rel.x(), centerChunkZ(is) + rel.z());
        terrain.copy(is.srcX() + rel.x(), is.srcZ() + rel.z(), target, done != null ? done : () -> { });
    }

    private void generateFlat(Island is, ChunkPos rel, boolean start) {
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
        if (generating.containsKey(island.owner())) { // terén se ještě kopíruje - počkáme
            waiting.computeIfAbsent(island.owner(), k -> new ArrayList<>())
                    .add(() -> teleport(player, island, after));
            return;
        }
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
    public void runCommands(Player player, String path) {
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

    /** initiator může být null (admin reset cizího ostrova, majitel offline). */
    public void resetIsland(Island island, Player initiator) {
        plugin.storage().backupIsland(island, "reset");
        plugin.removeRealmIslands(island.owner(), "reset"); // Nether a End ostrov se zahodí, vzniknou znovu přes portál
        plugin.panels().suspend(island); // během resetu se žádné cedulky nesmí objevit
        for (Player p : playersInside(island)) sendToReset(p);
        Bukkit.getScheduler().runTaskLater(plugin, () -> clearIsland(island, () -> {
            island.reset();
            beginGeneration(island);
            Runnable finish = () -> {
                scanResources(island, new ChunkPos(0, 0));
                endGeneration(island);
                try {
                    plugin.storage().saveIsland(island);
                } finally {
                    plugin.panels().resume(island);
                }
                if (initiator != null && initiator.isOnline()) {
                    plugin.messages().send(initiator, "reset.done");
                    if (plugin.getConfig().getBoolean("reset.teleport-back", true)) {
                        teleport(initiator, island, null);
                    }
                }
            };
            try {
                generateChunk(island, new ChunkPos(0, 0), true, finish);
            } catch (RuntimeException ex) {
                plugin.getLogger().log(java.util.logging.Level.WARNING, "Generování startovního chunku selhalo", ex);
                finish.run();
            }
        }), 20L);
    }

    public void deleteIsland(Island island) {
        plugin.storage().backupIsland(island, "delete");
        plugin.removeRealmIslands(island.owner(), "delete");
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
        plugin.panels().remove(island);
        islands.remove(island.owner());
        bySlot.remove(slotKey(island.gridX(), island.gridZ()));
        active.values().removeIf(owner -> owner.equals(island.owner()));
        plugin.storage().deleteIsland(dim, island.owner());
        saveState();
        clearing.add(island.owner());
        Bukkit.getScheduler().runTaskLater(plugin, () -> clearIsland(island, () -> clearing.remove(island.owner())), 20L);
    }

    public enum RestoreResult { OK, HAS_ISLAND, SLOT_TAKEN, CLEARING, NO_PARENT }

    /**
     * Obnoví ostrov ze zálohy: vrátí data (odemčené chunky, postup, členy, příspěvky) a znovu vygeneruje
     * terén všech odemčených chunků (ze stejného místa zdrojového světa). Co hráči postavili, se neobnoví.
     */
    public RestoreResult restoreIsland(Island restored) {
        if (dim != Dimension.OVERWORLD) { // ostrov v Netheru / Endu jde obnovit jen k existujícímu ostrovu v overworldu
            Island parent = plugin.islands().byOwner(restored.owner());
            if (parent == null) return RestoreResult.NO_PARENT;
            restored.setParent(parent);
        }
        if (islands.containsKey(restored.owner())) return RestoreResult.HAS_ISLAND;
        if (clearing.contains(restored.owner())) return RestoreResult.CLEARING;
        if (bySlot.containsKey(slotKey(restored.gridX(), restored.gridZ()))) return RestoreResult.SLOT_TAKEN;

        islands.put(restored.owner(), restored);
        bySlot.put(slotKey(restored.gridX(), restored.gridZ()), restored);
        if (pendingDelete.remove(restored.owner())) plugin.storage().savePendingDelete(pendingDelete);
        saveState();
        plugin.storage().saveIsland(restored);

        List<ChunkPos> chunks = new ArrayList<>();
        for (long key : restored.unlockedKeys()) chunks.add(ChunkPos.fromKey(key));
        chunks.sort(java.util.Comparator.comparingInt(ChunkPos::distance));

        plugin.panels().suspend(restored);
        beginGeneration(restored);
        regenerate(restored, chunks, 0, () -> {
            if (dim == Dimension.END) plugin.end().buildPodium(restored);
            endGeneration(restored);
            plugin.storage().saveIsland(restored);
            plugin.panels().resume(restored);
            plugin.panels().spawn(restored);
        });
        return RestoreResult.OK;
    }

    /** Generuje chunky jeden po druhém (kopírování terénu je asynchronní). */
    private void regenerate(Island is, List<ChunkPos> chunks, int index, Runnable done) {
        if (index >= chunks.size()) {
            done.run();
            return;
        }
        Runnable next = () -> regenerate(is, chunks, index + 1, done);
        try {
            generateChunk(is, chunks.get(index), false, next);
        } catch (RuntimeException ex) {
            plugin.getLogger().log(java.util.logging.Level.WARNING, "Obnova chunku selhala", ex);
            next.run();
        }
    }

    /**
     * Smaže ostrov v Netheru / Endu (volá se při resetu nebo smazání ostrova v overworldu): záloha, hráči uvnitř
     * se pošlou pryč, bloky se uklidí.
     */
    public void removeSilently(Island island, String reason) {
        plugin.storage().backupIsland(island, reason);
        for (Player p : playersInside(island)) sendTo(p, reason.equals("delete") ? "delete" : "reset");
        plugin.end().cleanup(island);
        plugin.panels().remove(island);
        islands.remove(island.owner());
        bySlot.remove(slotKey(island.gridX(), island.gridZ()));
        plugin.storage().deleteIsland(dim, island.owner());
        saveState();
        clearing.add(island.owner());
        Bukkit.getScheduler().runTaskLater(plugin, () -> clearIsland(island, () -> clearing.remove(island.owner())), 20L);
    }

    /** Vymaže bloky odemčených chunků, jeden chunk za tick. */
    private void clearIsland(Island island, Runnable done) {
        List<ChunkPos> chunks = new ArrayList<>();
        for (long key : island.unlockedKeys()) chunks.add(ChunkPos.fromKey(key));
        final int cx = centerChunkX(island);
        final int cz = centerChunkZ(island);
        final boolean full = island.hasSource();
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
                clearChunk(world.getChunkAt(cx + rel.x(), cz + rel.z()), full);
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    private void clearChunk(Chunk chunk, boolean full) {
        Settings s = plugin.settings();
        for (Entity e : chunk.getEntities()) {
            if (!(e instanceof Player)) e.remove();
        }
        int minY = full ? world.getMinHeight()
                : Math.max(world.getMinHeight(), s.surfaceY - s.fillerDepth - s.stoneDepth - 3);
        int maxY = full ? world.getMaxHeight() - 1 : Math.min(world.getMaxHeight() - 1, s.surfaceY + 64);
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
