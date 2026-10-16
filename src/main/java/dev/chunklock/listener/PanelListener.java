package dev.chunklock.listener;

import dev.chunklock.ChunkLockPlugin;
import dev.chunklock.model.ChunkPos;
import dev.chunklock.model.Island;
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.entity.EntitiesLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.persistence.PersistentDataType;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Kliknutí na cedulku zamčeného chunku: klik = odevzdat itemy, shift+klik = zaplatit. */
public final class PanelListener implements Listener {

    private final ChunkLockPlugin plugin;
    private final Map<UUID, Long> cooldown = new HashMap<>();

    public PanelListener(ChunkLockPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onRightClick(PlayerInteractEntityEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (e.getRightClicked() instanceof Interaction hitbox && isPanel(hitbox)) {
            e.setCancelled(true);
            handle(e.getPlayer(), hitbox);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onLeftClick(PrePlayerAttackEntityEvent e) {
        if (e.getAttacked() instanceof Interaction hitbox && isPanel(hitbox)) {
            e.setCancelled(true);
            handle(e.getPlayer(), hitbox);
        }
    }

    /** Cedulky se neukládají, ale kdyby nějaká zůstala na disku, při načtení chunku ji smažeme. */
    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent e) {
        for (Entity en : e.getEntities()) {
            if (plugin.panels().isOrphan(en)) en.remove();
        }
    }

    private boolean isPanel(Entity entity) {
        return entity.getPersistentDataContainer().has(plugin.panels().key(), PersistentDataType.STRING);
    }

    private void handle(Player player, Interaction hitbox) {
        long now = System.currentTimeMillis();
        Long last = cooldown.get(player.getUniqueId());
        if (last != null && now - last < 300) return;
        cooldown.put(player.getUniqueId(), now);

        String data = hitbox.getPersistentDataContainer().get(plugin.panels().key(), PersistentDataType.STRING);
        if (data == null) return;
        String[] parts = data.split(";");
        if (parts.length != 2) return;

        Island island;
        ChunkPos rel;
        try {
            island = plugin.islands().byOwner(UUID.fromString(parts[0]));
            rel = ChunkPos.fromKey(Long.parseLong(parts[1]));
        } catch (IllegalArgumentException ex) {
            return;
        }
        if (island == null) return;

        if (player.isSneaking()) {
            plugin.unlocks().pay(player, island, rel);
        } else {
            plugin.unlocks().deliver(player, island, rel);
        }
    }
}
