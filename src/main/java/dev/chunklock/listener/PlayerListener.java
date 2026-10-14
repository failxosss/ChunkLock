package dev.chunklock.listener;

import dev.chunklock.ChunkLockPlugin;
import dev.chunklock.manager.IslandManager.Plot;
import dev.chunklock.model.Island;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public final class PlayerListener implements Listener {

    private final ChunkLockPlugin plugin;

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
        for (Island island : plugin.islands().all()) {
            Island.Member member = island.members().get(player.getUniqueId());
            if (member != null && !member.name().equals(player.getName())) {
                member.setName(player.getName());
                plugin.storage().saveIsland(island);
            }
        }

        // hráč se odhlásil v chunk světě na ostrově, kam už nepatří
        if (plugin.islands().isChunkWorld(player.getWorld())) {
            Plot plot = plugin.islands().plotAt(player.getLocation());
            boolean ok = plot != null
                    && plot.island().isUnlocked(plot.rel())
                    && plot.island().isMember(player.getUniqueId());
            if (!ok && !player.hasPermission("chunklock.bypass.protection")) {
                plugin.islands().sendToSafe(player, null);
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        plugin.invites().clear(e.getPlayer().getUniqueId());
    }
}
