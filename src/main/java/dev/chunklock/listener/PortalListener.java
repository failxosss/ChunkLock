package dev.chunklock.listener;

import dev.chunklock.ChunkLockPlugin;
import dev.chunklock.manager.IslandManager;
import dev.chunklock.manager.IslandManager.Plot;
import dev.chunklock.model.Dimension;
import dev.chunklock.model.Island;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static dev.chunklock.config.Messages.ph;

/**
 * Portály v chunk světech: Nether portál vede na hráčův Nether ostrov, End portál na End ostrov (s drakem),
 * portál uvnitř Netheru / Endu zpět na ostrov v overworldu.
 */
public final class PortalListener implements Listener {

    private final ChunkLockPlugin plugin;
    private final Map<UUID, Long> last = new HashMap<>();

    public PortalListener(ChunkLockPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPortal(PlayerPortalEvent e) {
        TeleportCause cause = e.getCause();
        if (cause != TeleportCause.NETHER_PORTAL && cause != TeleportCause.END_PORTAL) return;
        World from = e.getFrom().getWorld();
        if (from == null) return;
        Dimension here = plugin.dimensionOf(from);
        boolean extra = here == null && plugin.settings().extraPortalWorlds.contains(from.getName().toLowerCase(Locale.ROOT));
        if (here == null && !extra) return; // obyčejný svět - vanilla portál

        e.setCancelled(true);
        Player player = e.getPlayer();
        long now = System.currentTimeMillis();
        Long prev = last.get(player.getUniqueId());
        if (prev != null && now - prev < 3000) return; // jeden průchod portálem = jedna událost
        last.put(player.getUniqueId(), now);

        Dimension target = cause == TeleportCause.NETHER_PORTAL ? Dimension.NETHER : Dimension.END;
        if (here == target) { // portál uvnitř Netheru / Endu vede zpět domů
            goHome(player, e);
            return;
        }
        if (here != null && here != Dimension.OVERWORLD) return; // např. End portál v Netheru - nikam

        Island owner;
        if (here == Dimension.OVERWORLD) {
            Plot plot = plugin.plotAt(e.getFrom());
            owner = plot != null ? plot.island() : plugin.islands().activeIsland(player.getUniqueId());
        } else {
            owner = plugin.islands().activeIsland(player.getUniqueId());
        }
        if (owner == null) {
            plugin.messages().send(player, "no-active-island");
            return;
        }
        if (!owner.isMember(player.getUniqueId())) return;

        IslandManager manager = plugin.islands(target);
        if (manager == null) {
            plugin.messages().send(player, "portal.disabled");
            return;
        }
        Island child = manager.byOwner(owner.owner());
        if (child == null) {
            child = manager.createChild(owner);
            plugin.messages().send(player, target == Dimension.NETHER ? "portal.created-nether" : "portal.created-end");
        }
        final Island dest = child;
        manager.teleport(player, dest, () -> {
            if (target == Dimension.END) plugin.end().ensureDragon(dest);
            plugin.messages().send(player, "portal.arrived", ph("dimension", target.id()), ph("owner", dest.ownerName()));
        });
    }

    private void goHome(Player player, PlayerPortalEvent e) {
        Plot plot = plugin.plotAt(e.getFrom());
        Island parent = plot != null ? plot.island().parent() : null;
        if (parent == null) parent = plugin.islands().activeIsland(player.getUniqueId());
        if (parent == null) {
            plugin.islands().sendToSafe(player, null);
            return;
        }
        plugin.islands().teleport(player, parent, null);
    }

    /** Moby a itemy nesmí portály do vanilla světů. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityPortal(EntityPortalEvent e) {
        if (e.getFrom().getWorld() != null && plugin.dimensionOf(e.getFrom().getWorld()) != null) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onDeath(EntityDeathEvent e) {
        plugin.end().onDragonDeath(e);
    }
}
