package dev.chunklock.manager;

import dev.chunklock.ChunkLockPlugin;
import dev.chunklock.model.ChunkPos;
import dev.chunklock.model.Dimension;
import dev.chunklock.model.Island;
import org.bukkit.Chunk;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Material;
import org.bukkit.World;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

/**
 * Zjišťuje, co jde v odemčeném chunku skutečně získat (rudy, dřevo, písek...).
 * Sken se dělá hned po vygenerování chunku (čistý stav, než ho hráč začne těžit) a ukládá se k ostrovu.
 * Z těchto čísel pak {@link UnlockService} počítá požadavky na odemčení sousedních chunků.
 */
public final class ResourceScanner {

    private final ChunkLockPlugin plugin;

    public ResourceScanner(ChunkLockPlugin plugin) {
        this.plugin = plugin;
    }

    /** Spočítá relevantní bloky (podle unlock.chunk.resources) v chunku, jak vypadá právě teď. */
    public Map<Material, Integer> scan(Chunk chunk, Dimension dim) {
        Set<Material> wanted = plugin.settings().blocksFor(dim);
        Map<Material, Integer> out = new EnumMap<>(Material.class);
        if (wanted.isEmpty()) return out;

        World w = chunk.getWorld();
        int minY = w.getMinHeight();
        int maxY = w.getMaxHeight();
        ChunkSnapshot snap = chunk.getChunkSnapshot(true, false, false);
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = minY; y < maxY; y++) {
                    Material type = snap.getBlockType(x, y, z);
                    if (type.isAir() || !wanted.contains(type)) continue;
                    out.merge(type, 1, Integer::sum);
                }
            }
        }
        return out;
    }

    /** Naskenuje (odemčený) chunk ostrova a výsledek uloží k ostrovu. */
    public Map<Material, Integer> record(Island island, ChunkPos rel) {
        IslandManager im = plugin.islandsFor(island);
        Chunk chunk = im.world().getChunkAt(im.centerChunkX(island) + rel.x(), im.centerChunkZ(island) + rel.z());
        Map<Material, Integer> counts = scan(chunk, island.dimension());
        island.setResources(rel, counts);
        return counts;
    }

    /** Uložený sken chunku; u starších ostrovů, které ho nemají, se chunk naskenuje podle aktuálního stavu. */
    public Map<Material, Integer> get(Island island, ChunkPos rel) {
        Map<Material, Integer> saved = island.resources(rel);
        return saved != null ? saved : record(island, rel);
    }
}
