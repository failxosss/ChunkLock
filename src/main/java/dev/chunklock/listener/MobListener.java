package dev.chunklock.listener;

import dev.chunklock.ChunkLockPlugin;
import dev.chunklock.manager.IslandManager.Plot;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason;

import java.util.EnumSet;
import java.util.Set;

/** Majitel si může vypnout spawnování mobů na svém chunku (/chunk mobs). */
public final class MobListener implements Listener {

    /** Automatické spawny. Rozmnožování, spawn vejce, příkazy a pluginy zůstávají povolené. */
    private static final Set<SpawnReason> BLOCKED = EnumSet.of(
            SpawnReason.NATURAL, SpawnReason.SPAWNER, SpawnReason.TRAP, SpawnReason.REINFORCEMENTS,
            SpawnReason.RAID, SpawnReason.PATROL, SpawnReason.VILLAGE_INVASION, SpawnReason.JOCKEY,
            SpawnReason.MOUNT, SpawnReason.NETHER_PORTAL, SpawnReason.DEFAULT);

    private final ChunkLockPlugin plugin;

    public MobListener(ChunkLockPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent e) {
        if (!BLOCKED.contains(e.getSpawnReason())) return;
        if (!plugin.islands().isChunkWorld(e.getLocation().getWorld())) return;
        Plot plot = plugin.islands().plotAt(e.getLocation());
        if (plot != null && !plot.island().mobSpawning()) e.setCancelled(true);
    }
}
