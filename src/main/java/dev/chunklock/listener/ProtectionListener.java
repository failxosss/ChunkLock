package dev.chunklock.listener;

import dev.chunklock.ChunkLockPlugin;
import dev.chunklock.manager.IslandManager.Plot;
import dev.chunklock.model.IslandPermission;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Container;
import org.bukkit.block.data.Directional;
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
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.world.StructureGrowEvent;
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

    /** Je na dané pozici zamčený chunk (nebo mimo ostrov) v chunk světě? */
    private boolean lockedAt(Location loc) {
        if (loc == null || loc.getWorld() == null) return false;
        if (!plugin.islands().isChunkWorld(loc.getWorld())) return false;
        Plot plot = plugin.islands().plotAt(loc);
        return plot == null || !plot.island().isUnlocked(plot.rel());
    }

    private boolean lockedAt(Block block) {
        return lockedAt(block.getLocation());
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
        if (!allowed(e.getPlayer(), e.getBlock().getLocation(), IslandPermission.BUILD, true)
                || !allowed(e.getPlayer(), e.getBlockClicked().getLocation(), IslandPermission.BUILD, true)) {
            e.setCancelled(true);
        }
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

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHangingPlace(HangingPlaceEvent e) {
        Player p = e.getPlayer();
        if (p != null) {
            if (!allowed(p, e.getEntity().getLocation(), IslandPermission.BUILD, true)) e.setCancelled(true);
        } else if (lockedAt(e.getEntity().getLocation())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityPlace(EntityPlaceEvent e) {
        Player p = e.getPlayer();
        if (p != null) {
            if (!allowed(p, e.getEntity().getLocation(), IslandPermission.BUILD, true)) e.setCancelled(true);
        } else if (lockedAt(e.getEntity().getLocation())) {
            e.setCancelled(true);
        }
    }

    // ---------------------------------------------------------------- zamčené chunky: automatika, voda, růst

    /** Voda / láva / dračí vejce nesmí téct do zamčeného chunku (ani na jiný ostrov). */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFluidFlow(BlockFromToEvent e) {
        Block from = e.getBlock();
        Block to = e.getToBlock();
        if (!plugin.islands().isChunkWorld(from.getWorld())) return;

        Plot target = plugin.islands().plotAt(to.getLocation());
        if (target == null || !target.island().isUnlocked(target.rel())) {
            e.setCancelled(true);
            return;
        }
        // tok mezi dvěma různými ostrovy zakázat
        if ((from.getX() >> 4) != (to.getX() >> 4) || (from.getZ() >> 4) != (to.getZ() >> 4)) {
            Plot source = plugin.islands().plotAt(from.getLocation());
            if (source == null || source.island() != target.island()) e.setCancelled(true);
        }
    }

    /** Dispenser nesmí vylít vodu/lávu, položit blok, vyvolat entitu apod. do zamčeného chunku. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDispense(BlockDispenseEvent e) {
        Block b = e.getBlock();
        if (!plugin.islands().isChunkWorld(b.getWorld())) return;
        if (lockedAt(b)) {
            e.setCancelled(true);
            return;
        }
        if (b.getBlockData() instanceof Directional d) {
            if (lockedAt(b.getRelative(d.getFacing()))) e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent e) {
        if (!plugin.islands().isChunkWorld(e.getBlock().getWorld())) return;
        BlockFace dir = e.getDirection();
        if (lockedAt(e.getBlock().getRelative(dir))) {
            e.setCancelled(true);
            return;
        }
        for (Block moved : e.getBlocks()) {
            if (lockedAt(moved) || lockedAt(moved.getRelative(dir))) {
                e.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent e) {
        if (!plugin.islands().isChunkWorld(e.getBlock().getWorld())) return;
        BlockFace dir = e.getDirection();
        for (Block moved : e.getBlocks()) {
            if (lockedAt(moved) || lockedAt(moved.getRelative(dir))) {
                e.setCancelled(true);
                return;
            }
        }
    }

    /** Vznik/šíření bloků: led, sníh, obsidián, beton, tráva, houby, sculk, oheň... */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockForm(BlockFormEvent e) {
        if (lockedAt(e.getBlock())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent e) {
        if (lockedAt(e.getBlock())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent e) {
        if (lockedAt(e.getBlock())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onStructureGrow(StructureGrowEvent e) {
        if (!plugin.islands().isChunkWorld(e.getWorld())) return;
        if (lockedAt(e.getLocation())) {
            e.setCancelled(true);
            return;
        }
        e.getBlocks().removeIf(state -> lockedAt(state.getLocation()));
    }

    /** Padající bloky, endermani, atd. nesmí měnit bloky v zamčeném chunku. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent e) {
        if (lockedAt(e.getBlock())) e.setCancelled(true);
    }

    // ---------------------------------------------------------------- výbuchy

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent e) {
        if (!plugin.islands().isChunkWorld(e.getLocation().getWorld())) return;
        if (!plugin.settings().explosions) {
            e.blockList().clear();
        } else {
            e.blockList().removeIf(this::lockedAt);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        if (!plugin.islands().isChunkWorld(e.getBlock().getWorld())) return;
        if (!plugin.settings().explosions) {
            e.blockList().clear();
        } else {
            e.blockList().removeIf(this::lockedAt);
        }
    }
}
