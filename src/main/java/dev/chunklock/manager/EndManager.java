package dev.chunklock.manager;

import dev.chunklock.ChunkLockPlugin;
import dev.chunklock.model.Dimension;
import dev.chunklock.model.Island;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Directional;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * End ostrovy: výstupní portál (podium) uprostřed startovního chunku a vlastní drak pro každý ostrov.
 * Drak vzniká přes API (bez vanilla souboje), proto má vlastní boss bar, "vodítko" a aktivaci portálu po smrti.
 */
public final class EndManager {

    private final ChunkLockPlugin plugin;
    /** Značka na drakovi: UUID majitele ostrova. */
    private final NamespacedKey dragonKey;
    private final Map<UUID, Tracker> trackers = new HashMap<>();

    private static final class Tracker {
        final BossBar bar;
        final Set<UUID> viewers = new HashSet<>();
        BukkitRunnable task;

        Tracker(Component title) {
            bar = BossBar.bossBar(title, 1f, BossBar.Color.PINK, BossBar.Overlay.PROGRESS);
        }
    }

    public EndManager(ChunkLockPlugin plugin) {
        this.plugin = plugin;
        this.dragonKey = new NamespacedKey(plugin, "end_dragon");
    }

    private IslandManager manager() {
        return plugin.islands(Dimension.END);
    }

    // ---------------------------------------------------------------- podium s portálem

    private int originX(Island is) {
        return (manager().centerChunkX(is) << 4) + 8;
    }

    private int originZ(Island is) {
        return (manager().centerChunkZ(is) << 4) + 8;
    }

    /** Střed podia (výška pevně uložená u ostrova, aby se při přestavbě neposouvala). */
    private Location origin(Island is) {
        World w = manager().world();
        if (!is.hasRefY()) {
            is.setRefY(w.getHighestBlockYAt(originX(is), originZ(is), HeightMap.MOTION_BLOCKING_NO_LEAVES) + 1);
        }
        return new Location(w, originX(is), is.refY(), originZ(is));
    }

    /**
     * Postaví výstupní portál jako ve vanilla Endu: bedrockový kruh, sloupek s pochodněmi a uprostřed portál
     * (aktivní až po zabití draka). Dá se volat opakovaně.
     */
    public void buildPodium(Island is) {
        if (manager() == null) return;
        Location o = origin(is);
        World w = o.getWorld();
        int ox = o.getBlockX();
        int oy = o.getBlockY();
        int oz = o.getBlockZ();
        boolean active = is.dragonKilled();
        int maxY = Math.min(w.getMaxHeight() - 1, oy + 32);
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                double r = Math.sqrt(dx * dx + dz * dz);
                if (r > 3.5) continue;
                boolean inner = r <= 2.5;
                for (int y = oy - 1; y <= maxY; y++) {
                    Block b = w.getBlockAt(ox + dx, y, oz + dz);
                    if (y < oy) {
                        b.setType(inner ? Material.BEDROCK : Material.END_STONE, false);
                    } else if (y > oy) {
                        b.setType(Material.AIR, false);
                    } else if (!inner) {
                        b.setType(Material.BEDROCK, false);
                    } else {
                        b.setType(active ? Material.END_PORTAL : Material.AIR, false);
                    }
                }
            }
        }
        for (int i = 0; i < 4; i++) w.getBlockAt(ox, oy + i, oz).setType(Material.BEDROCK, false);
        torch(w, ox, oy + 2, oz - 1, BlockFace.NORTH);
        torch(w, ox, oy + 2, oz + 1, BlockFace.SOUTH);
        torch(w, ox + 1, oy + 2, oz, BlockFace.EAST);
        torch(w, ox - 1, oy + 2, oz, BlockFace.WEST);
    }

    private static void torch(World w, int x, int y, int z, BlockFace face) {
        Block b = w.getBlockAt(x, y, z);
        b.setType(Material.WALL_TORCH, false);
        if (b.getBlockData() instanceof Directional t) {
            t.setFacing(face);
            b.setBlockData(t, false);
        }
    }

    // ---------------------------------------------------------------- drak

    private EnderDragon findDragon(Island is) {
        World w = manager().world();
        String owner = is.owner().toString();
        for (EnderDragon d : w.getEntitiesByClass(EnderDragon.class)) {
            if (owner.equals(d.getPersistentDataContainer().get(dragonKey, PersistentDataType.STRING))) return d;
        }
        return null;
    }

    /** Hráč vstoupil na End ostrov: když drak ještě nebyl zabit a žádný tu neletí, vznikne nový. */
    public void ensureDragon(Island is) {
        if (manager() == null || is.dimension() != Dimension.END) return;
        if (is.dragonKilled()) {
            buildPodium(is); // portál musí být aktivní i po restartu
            return;
        }
        if (!plugin.settings().dragonEnabled) return;
        EnderDragon existing = findDragon(is);
        if (existing != null) {
            if (!trackers.containsKey(existing.getUniqueId())) track(existing, is);
            return;
        }
        Location podium = origin(is).add(0.5, 0, 0.5);
        World w = podium.getWorld();
        Location spawn = podium.clone().add(0, Math.min(30, w.getMaxHeight() - 5 - podium.getY()), 0);
        EnderDragon dragon = w.spawn(spawn, EnderDragon.class, d -> {
            d.getPersistentDataContainer().set(dragonKey, PersistentDataType.STRING, is.owner().toString());
            d.setRemoveWhenFarAway(false);
            d.setPodium(podium);
            d.setPhase(EnderDragon.Phase.CIRCLING);
        });
        track(dragon, is);
    }

    private void track(EnderDragon dragon, Island is) {
        Tracker t = new Tracker(plugin.messages().get("end.dragon-title"));
        trackers.put(dragon.getUniqueId(), t);
        Location podium = origin(is).add(0.5, 0, 0.5);
        double leash = plugin.settings().dragonLeash;
        t.task = new BukkitRunnable() {
            @Override
            @SuppressWarnings("deprecation")
            public void run() {
                if (!dragon.isValid() || dragon.isDead()) {
                    stop(dragon.getUniqueId());
                    return;
                }
                // vodítko: drak nesmí odletět z ostrova (vanilla dráhy letu jsou kolem středu světa)
                Location l = dragon.getLocation();
                double dx = l.getX() - podium.getX();
                double dz = l.getZ() - podium.getZ();
                if (dx * dx + dz * dz > leash * leash) {
                    Location back = podium.clone().add(0, 30, 0);
                    dragon.teleport(back);
                    dragon.setPhase(EnderDragon.Phase.CIRCLING);
                }
                // boss bar pro hráče poblíž
                double max = Math.max(1.0, dragon.getMaxHealth());
                t.bar.progress((float) Math.max(0.0, Math.min(1.0, dragon.getHealth() / max)));
                Set<UUID> now = new HashSet<>();
                for (Player p : dragon.getWorld().getPlayers()) {
                    double px = p.getLocation().getX() - podium.getX();
                    double pz = p.getLocation().getZ() - podium.getZ();
                    if (px * px + pz * pz <= (leash + 64) * (leash + 64)) {
                        now.add(p.getUniqueId());
                        if (t.viewers.add(p.getUniqueId())) p.showBossBar(t.bar);
                    }
                }
                for (UUID id : new ArrayList<>(t.viewers)) {
                    if (now.contains(id)) continue;
                    Player p = Bukkit.getPlayer(id);
                    if (p != null) p.hideBossBar(t.bar);
                    t.viewers.remove(id);
                }
            }
        };
        t.task.runTaskTimer(plugin, 10L, 10L);
    }

    private void stop(UUID dragonId) {
        Tracker t = trackers.remove(dragonId);
        if (t == null) return;
        if (t.task != null) t.task.cancel();
        for (UUID id : t.viewers) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) p.hideBossBar(t.bar);
        }
        t.viewers.clear();
    }

    /** Drak zemřel: ostrov si to zapamatuje, po dokončení animace se aktivuje portál a objeví se vejce. */
    public void onDragonDeath(EntityDeathEvent e) {
        if (!(e.getEntity() instanceof EnderDragon dragon)) return;
        String ownerStr = dragon.getPersistentDataContainer().get(dragonKey, PersistentDataType.STRING);
        if (ownerStr == null || manager() == null) return;
        stop(dragon.getUniqueId());
        Island is;
        try {
            is = manager().byOwner(UUID.fromString(ownerStr));
        } catch (IllegalArgumentException ex) {
            return;
        }
        if (is == null) return;
        boolean first = !is.dragonKilled();
        if (first) e.setDroppedExp(12000); // jako vanilla při prvním zabití
        is.setDragonKilled(true);
        plugin.storage().saveIsland(is);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (manager().byOwner(is.owner()) != is) return; // ostrov mezitím zmizel
            buildPodium(is);
            Location o = origin(is);
            if (first) o.getWorld().getBlockAt(o.getBlockX(), o.getBlockY() + 4, o.getBlockZ())
                    .setType(Material.DRAGON_EGG, false);
            for (Player p : o.getWorld().getPlayers()) {
                if (is.isMember(p.getUniqueId())) plugin.messages().send(p, "end.dragon-killed");
            }
        }, 160L);
    }

    /** Smazání / reset ostrova: drak a boss bary pryč. */
    public void cleanup(Island is) {
        if (is.dimension() != Dimension.END || manager() == null) return;
        EnderDragon d = findDragon(is);
        if (d != null) {
            stop(d.getUniqueId());
            d.remove();
        }
    }

    /** Vypnutí pluginu: boss bary pryč (draci zůstávají ve světě). */
    public void shutdown() {
        for (UUID id : new ArrayList<>(trackers.keySet())) stop(id);
    }
}
