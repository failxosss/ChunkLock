package dev.chunklock.listener;

import dev.chunklock.ChunkLockPlugin;
import dev.chunklock.manager.IslandManager.Plot;
import dev.chunklock.model.IslandPermission;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Hanging;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Ochrana chunk světa podle oprávnění členů. */
public final class ProtectionListener implements Listener {

    private static final String BYPASS = "chunklock.bypass.protection";

    private final ChunkLockPlugin plugin;
    private final Map<UUID, Long> lastMessage = new HashMap<>();

    public ProtectionListener(ChunkLockPlugin plugin) {
        this.plugin = plugin;
    }

    private boolean allowed(Player player, Location loc, IslandPermission permission, boolean notify) {
        if (!plugin.islands().isChunkWorld(loc.getWorld())) return true;
        if (player.hasPermission(BYPASS)) return true;

        Plot plot = plugin.islands().plotAt(loc);
        if (plot == null || !plot.island().isUnlocked(plot.rel())) {
            if (notify) deny(player, "protection.locked");
            return false;
        }
        if (!plot.island().hasPermission(player.getUniqueId(), permission)) {
            if (notify) deny(player, "protection.denied");
            return false;
        }
        return true;
    }

    private void deny(Player player, String key) {
        long now = System.currentTimeMillis();
        Long last = lastMessage.get(player.getUniqueId());
        if (last != null && now - last < 1000) return;
        lastMessage.put(player.getUniqueId(), now);
        plugin.messages().actionBar(player, key);
    }

    // ---------------------------------------------------------------- bloky

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (!allowed(e.getPlayer(), e.getBlock().getLocation(), IslandPermission.BREAK, true)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (!allowed(e.getPlayer(), e.getBlock().getLocation(), IslandPermission.BUILD, true)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent e) {
        if (!allowed(e.getPlayer(), e.getBlock().getLocation(), IslandPermission.BUILD, true)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent e) {
        if (!allowed(e.getPlayer(), e.getBlock().getLocation(), IslandPermission.BREAK, true)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent e) {
        Block block = e.getClickedBlock();
        if (block == null) return;
        Player player = e.getPlayer();

        if (e.getAction() == Action.PHYSICAL) {
            if (!allowed(player, block.getLocation(), IslandPermission.INTERACT, false)) e.setCancelled(true);
            return;
        }
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK) return;

        ItemStack item = e.getItem();
        boolean holdingItem = item != null && !item.getType().isAir();

        if (block.getState(false) instanceof Container) {
            if (!allowed(player, block.getLocation(), IslandPermission.CONTAINERS, true)) e.setCancelled(true);
        } else if (block.getType().isInteractable() && !(player.isSneaking() && holdingItem)) {
            if (!allowed(player, block.getLocation(), IslandPermission.INTERACT, true)) e.setCancelled(true);
        } else if (holdingItem && isBuildItem(item.getType())) {
            if (!allowed(player, block.getLocation(), IslandPermission.BUILD, true)) e.setCancelled(true);
        }
    }

    private static boolean isBuildItem(Material m) {
        String n = m.name().toLowerCase(Locale.ROOT);
        return m == Material.BONE_MEAL || m == Material.FLINT_AND_STEEL || m == Material.FIRE_CHARGE
                || m == Material.ARMOR_STAND || m == Material.PAINTING || m == Material.ITEM_FRAME
                || m == Material.GLOW_ITEM_FRAME || m == Material.END_CRYSTAL
                || n.endsWith("_hoe") || n.endsWith("_axe") || n.endsWith("_shovel")
                || n.endsWith("_spawn_egg") || n.endsWith("_boat") || n.endsWith("_raft")
                || n.endsWith("minecart");
    }

    // ---------------------------------------------------------------- entity

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityInteract(PlayerInteractEntityEvent e) {
        Entity entity = e.getRightClicked();
        if (entity instanceof Interaction) return; // cedulky řeší PanelListener
        IslandPermission need = (entity instanceof LivingEntity && !(entity instanceof ArmorStand))
                ? IslandPermission.ANIMALS : IslandPermission.INTERACT;
        if (!allowed(e.getPlayer(), entity.getLocation(), need, true)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent e) {
        Player damager = null;
        if (e.getDamager() instanceof Player p) {
            damager = p;
        } else if (e.getDamager() instanceof Projectile proj && proj.getShooter() instanceof Player p) {
            damager = p;
        }
        if (damager == null) return;

        Entity victim = e.getEntity();
        if (!plugin.islands().isChunkWorld(victim.getWorld())) return;
        if (victim instanceof Interaction) return;

        if (victim instanceof Player) {
            if (!plugin.settings().pvp) e.setCancelled(true);
            return;
        }
        if (victim instanceof Enemy) return; // monstra smí každý

        IslandPermission need;
        if (victim instanceof ArmorStand || victim instanceof Hanging) {
            need = IslandPermission.BREAK;
        } else if (victim instanceof LivingEntity) {
            need = IslandPermission.ANIMALS;
        } else {
            need = IslandPermission.BREAK;
        }
        if (!allowed(damager, victim.getLocation(), need, true)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHangingBreak(HangingBreakByEntityEvent e) {
        if (e.getRemover() instanceof Player p) {
            if (!allowed(p, e.getEntity().getLocation(), IslandPermission.BREAK, true)) e.setCancelled(true);
        }
    }

    // ---------------------------------------------------------------- výbuchy

    @EventHandler(ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent e) {
        if (!plugin.settings().explosions && plugin.islands().isChunkWorld(e.getLocation().getWorld())) {
            e.blockList().clear();
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        if (!plugin.settings().explosions && plugin.islands().isChunkWorld(e.getBlock().getWorld())) {
            e.blockList().clear();
        }
    }
}
