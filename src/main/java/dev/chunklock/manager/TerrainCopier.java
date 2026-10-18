package dev.chunklock.manager;

import dev.chunklock.ChunkLockPlugin;
import dev.chunklock.config.Settings;
import dev.chunklock.model.Dimension;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.ChunkSnapshot;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * Kopíruje chunky z normálního (zdrojového) světa do void světa pluginu:
 * bloky, biomy, jeskyně, rudy, struktury i bedny/spawnery. Kopírování je rozložené do více ticků.
 */
public final class TerrainCopier {

    private final ChunkLockPlugin plugin;
    private final Dimension dimension;
    private World source;

    public TerrainCopier(ChunkLockPlugin plugin, Dimension dimension) {
        this.plugin = plugin;
        this.dimension = dimension;
    }

    /** Načte nebo vytvoří zdrojový svět. @return false při chybě */
    public boolean init() {
        Settings s = plugin.settings();
        Settings.DimensionSettings ds = s.dimension(dimension);
        if (ds.sourceWorld().equalsIgnoreCase(ds.world())) {
            plugin.getLogger().severe("Zdrojový svět (" + dimension.id() + ") nesmí být stejný jako svět s ostrovy.");
            return false;
        }
        World w = Bukkit.getWorld(ds.sourceWorld());
        if (w == null) {
            WorldCreator wc = new WorldCreator(ds.sourceWorld())
                    .environment(dimension.environment())
                    .type(WorldType.NORMAL)
                    .generateStructures(s.sourceStructures);
            if (ds.seed() != null) wc.seed(ds.seed());
            w = wc.createWorld();
        }
        if (w == null) {
            plugin.getLogger().severe("Nepodařilo se vytvořit zdrojový svět '" + ds.sourceWorld() + "'.");
            return false;
        }
        w.setAutoSave(false); // zdrojový svět nemá zbytečně zabírat místo na disku
        source = w;
        return true;
    }

    public World source() {
        return source;
    }

    // ---------------------------------------------------------------- hledání startu

    /** Najde náhodné místo ve zdrojovém světě, které je souš (ne oceán/strom). Vrací souřadnice chunku. */
    public void findStart(Consumer<int[]> callback) {
        tryStart(0, callback);
    }

    private void tryStart(int attempt, Consumer<int[]> callback) {
        Settings s = plugin.settings();
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        int range = s.dimension(dimension).startRange();
        int cx = rnd.nextInt(-range, range + 1);
        int cz = rnd.nextInt(-range, range + 1);
        if (attempt >= s.startAttempts) {
            callback.accept(new int[]{cx, cz}); // nic vhodného - vezmeme, co máme
            return;
        }
        source.getChunkAtAsync(cx, cz, true).whenComplete((chunk, err) ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    boolean ok = err == null && chunk != null && suitable(chunk);
                    if (ok) {
                        callback.accept(new int[]{cx, cz});
                        return;
                    }
                    if (chunk != null) chunk.unload(false);
                    tryStart(attempt + 1, callback);
                }));
    }

    /**
     * Najde místo k stání (y nohou) ve sloupci x,z: pevný blok a nad ním dva volné bloky. Hledá se odshora dolů
     * od topY; vrací Integer.MIN_VALUE, když nic takového není. Slouží pro Nether (strop z bedrocku, láva dole).
     */
    public static int findFloorY(World w, int x, int z, int topY, int bottomY) {
        for (int y = topY; y >= bottomY; y--) {
            Block below = w.getBlockAt(x, y - 1, z);
            if (!below.getType().isSolid() || below.getType() == Material.BEDROCK) continue;
            if (w.getBlockAt(x, y, z).isPassable() && !w.getBlockAt(x, y, z).isLiquid()
                    && w.getBlockAt(x, y + 1, z).isPassable() && !w.getBlockAt(x, y + 1, z).isLiquid()) {
                return y;
            }
        }
        return Integer.MIN_VALUE;
    }

    private boolean suitable(Chunk chunk) {
        if (dimension == Dimension.NETHER) {
            int x = (chunk.getX() << 4) + 8;
            int z = (chunk.getZ() << 4) + 8;
            int y = findFloorY(chunk.getWorld(), x, z, 110, 36);
            return y != Integer.MIN_VALUE;
        }
        World w = chunk.getWorld();
        int x = (chunk.getX() << 4) + 8;
        int z = (chunk.getZ() << 4) + 8;
        int y = w.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
        Block b = w.getBlockAt(x, y, z);
        Material m = b.getType();
        if (b.isLiquid() || m == Material.ICE || m == Material.PACKED_ICE || m == Material.POWDER_SNOW) return false;
        if (Tag.LOGS.isTagged(m) || Tag.LEAVES.isTagged(m)) return false;
        return y >= 63 && y <= 150;
    }

    // ---------------------------------------------------------------- kopírování

    /** Zkopíruje chunk (srcX, srcZ) ze zdrojového světa do cílového chunku. done se zavolá vždy. */
    public void copy(int srcX, int srcZ, Chunk target, Runnable done) {
        source.getChunkAtAsync(srcX, srcZ, true).whenComplete((chunk, err) ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (err != null || chunk == null) {
                        plugin.getLogger().log(Level.WARNING, "Nepodařilo se vygenerovat zdrojový chunk "
                                + srcX + "," + srcZ, err);
                        done.run();
                        return;
                    }
                    try {
                        start(chunk, target, done);
                    } catch (RuntimeException ex) {
                        plugin.getLogger().log(Level.WARNING, "Kopírování chunku selhalo", ex);
                        done.run();
                    }
                }));
    }

    private void start(Chunk src, Chunk dst, Runnable done) {
        World dw = dst.getWorld();
        ChunkSnapshot snap = src.getChunkSnapshot(false, true, false);
        BlockState[] tiles = src.getTileEntities();
        int minY = Math.max(source.getMinHeight(), dw.getMinHeight());
        int maxY = Math.min(source.getMaxHeight(), dw.getMaxHeight()) - 1;
        int cx = dst.getX();
        int cz = dst.getZ();
        int baseX = cx << 4;
        int baseZ = cz << 4;
        int layers = Math.max(1, plugin.settings().layersPerTick);
        dw.addPluginChunkTicket(cx, cz, plugin); // chunk se během kopírování nesmí odnačíst

        new BukkitRunnable() {
            int y = minY;

            @Override
            public void run() {
                try {
                    int end = Math.min(maxY, y + layers - 1);
                    for (int yy = y; yy <= end; yy++) {
                        for (int x = 0; x < 16; x++) {
                            for (int z = 0; z < 16; z++) {
                                BlockData data = snap.getBlockData(x, yy, z);
                                if (data.getMaterial().isAir()) continue;
                                dst.getBlock(x, yy, z).setBlockData(data, false);
                            }
                        }
                    }
                    y = end + 1;
                    if (y <= maxY) return;
                    cancel();
                    finish();
                } catch (RuntimeException ex) {
                    cancel();
                    plugin.getLogger().log(Level.WARNING, "Kopírování chunku selhalo", ex);
                    dw.removePluginChunkTicket(cx, cz, plugin);
                    done.run();
                }
            }

            @SuppressWarnings("deprecation")
            private void finish() {
                try {
                    for (int bx = 0; bx < 16; bx += 4) {
                        for (int bz = 0; bz < 16; bz += 4) {
                            for (int by = minY; by <= maxY; by += 4) {
                                dw.setBiome(baseX + bx, by, baseZ + bz, snap.getBiome(bx, by, bz));
                            }
                        }
                    }
                    int srcBaseX = src.getX() << 4;
                    int srcBaseZ = src.getZ() << 4;
                    for (BlockState st : tiles) {
                        Location loc = new Location(dw, baseX + (st.getX() - srcBaseX), st.getY(),
                                baseZ + (st.getZ() - srcBaseZ));
                        try {
                            st.copy(loc).update(true, false);
                        } catch (RuntimeException ignored) {
                            // blok bez speciálních dat - nevadí
                        }
                    }
                    dw.refreshChunk(cx, cz);
                } catch (RuntimeException ex) {
                    plugin.getLogger().log(Level.WARNING, "Dokončení kopírování chunku selhalo", ex);
                } finally {
                    dw.removePluginChunkTicket(cx, cz, plugin);
                    src.unload(false);
                    done.run();
                }
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }
}
