package dev.chunklock.listener;

import dev.chunklock.ChunkLockPlugin;
import dev.chunklock.manager.IslandManager.Plot;
import dev.chunklock.model.Island;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public final class PlayerListener implements Listener {

    private final ChunkLockPlugin plugin;
    /** Hráči, kteří zemřeli v chunk světě - po respawnu se vrátí na svůj chunk. */
    private final Set<UUID> diedInChunkWorld = new HashSet<>();

    public PlayerListener(ChunkLockPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player player = e.getPlayer();

        // aktualizace jména (změna nicku)
        Island own = plugin.islands().byOwner(player.getUniqueId());
        if (own != null && !own.ownerName().equals(player.getName())) {
            own.setOwnerName(player.getName());
            plugin.storage().saveIsland(own);
        }
        for (dev.chunklock.manager.IslandManager m : plugin.allIslandManagers()) {
            for (Island island : m.all()) {
                if (island.parent() != null) continue; // členy sdílí s hlavním ostrovem
                Island.Member member = island.members().get(player.getUniqueId());
                if (member != null && !member.name().equals(player.getName())) {
                    member.setName(player.getName());
                    plugin.storage().saveIsland(island);
                }
            }
        }

        // ostrov mu byl smazán adminem, když byl offline - teleport na delete.location
        if (plugin.islands().applyPendingDelete(player)) return;

        // hráč se odhlásil v chunk světě na ostrově, kam už nepatří
        if (plugin.isChunkWorld(player.getWorld())) {
            Plot plot = plugin.plotAt(player.getLocation());
            boolean ok = plot != null
                    && plot.island().isUnlocked(plot.rel())
                    && plot.island().isMember(player.getUniqueId());
            if (!ok && !player.hasPermission("chunklock.bypass.protection")) {
                plugin.islands().sendToSafe(player, null);
            }
        }
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        Player player = e.getEntity();
        if (plugin.isChunkWorld(player.getWorld())) {
            diedInChunkWorld.add(player.getUniqueId());
        }
    }

    /** Smrt v chunk světě = respawn na vlastním (aktivním) chunku. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onRespawn(PlayerRespawnEvent e) {
        Player player = e.getPlayer();
        if (!diedInChunkWorld.remove(player.getUniqueId())) return;
        Island island = plugin.islands().activeIsland(player.getUniqueId());
        if (island == null) return;
        e.setRespawnLocation(plugin.islands().spawnLocation(island));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        plugin.invites().clear(e.getPlayer().getUniqueId());
        diedInChunkWorld.remove(e.getPlayer().getUniqueId());
    }
}
