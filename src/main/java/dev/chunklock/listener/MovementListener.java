package dev.chunklock.listener;

import dev.chunklock.ChunkLockPlugin;
import dev.chunklock.manager.IslandManager;
import dev.chunklock.manager.IslandManager.Plot;
import dev.chunklock.model.Island;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Zamčené chunky nejdou přejít + záchrana při pádu do voidu. */
public final class MovementListener implements Listener {

    private static final String BYPASS = "chunklock.bypass.protection";

    private final ChunkLockPlugin plugin;
    private final Map<UUID, Long> lastMessage = new HashMap<>();
    private final Map<UUID, Long> lastRescue = new HashMap<>();

    public MovementListener(ChunkLockPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        Location from = e.getFrom();
        Location to = e.getTo();
        if (to == null) return;
        IslandManager im = plugin.islandsAt(to.getWorld());
        if (im == null) return;

        Player player = e.getPlayer();
        if (player.hasPermission(BYPASS)) return;

        if (plugin.settings().voidRescue && to.getY() < im.rescueY(to)) {
            rescue(player);
            return;
        }

        if ((to.getBlockX() >> 4) == (from.getBlockX() >> 4) && (to.getBlockZ() >> 4) == (from.getBlockZ() >> 4)) {
            return;
        }

        Plot plot = im.plotAt(to);
        if (plot == null || !plot.island().isUnlocked(plot.rel())) {
            Location back = from.clone();
            back.setYaw(to.getYaw());
            back.setPitch(to.getPitch());
            e.setTo(back);
            long now = System.currentTimeMillis();
            Long last = lastMessage.get(player.getUniqueId());
            if (last == null || now - last > 1000) {
                lastMessage.put(player.getUniqueId(), now);
                plugin.messages().actionBar(player, "protection.locked");
            }
        }
    }

    private void rescue(Player player) {
        long now = System.currentTimeMillis();
        Long last = lastRescue.get(player.getUniqueId());
        if (last != null && now - last < 2000) return;
        lastRescue.put(player.getUniqueId(), now);

        player.setFallDistance(0);
        Plot plot = plugin.plotAt(player.getLocation());
        Island island = plot != null ? plot.island() : plugin.islands().activeIsland(player.getUniqueId());
        if (island != null) {
            plugin.islandsFor(island).teleport(player, island, null);
        } else {
            plugin.islands().sendToSafe(player, null);
        }
    }

    /** Ender perly a chorus fruit nesmí do zamčených chunků. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        PlayerTeleportEvent.TeleportCause cause = e.getCause();
        if (cause != PlayerTeleportEvent.TeleportCause.ENDER_PEARL
                && cause != PlayerTeleportEvent.TeleportCause.CHORUS_FRUIT) return;
        Location to = e.getTo();
        if (!plugin.isChunkWorld(to.getWorld())) return;
        if (e.getPlayer().hasPermission(BYPASS)) return;
        Plot plot = plugin.plotAt(to);
        if (plot == null || !plot.island().isUnlocked(plot.rel())) {
            e.setCancelled(true);
            plugin.messages().actionBar(e.getPlayer(), "protection.locked");
        }
    }
}
