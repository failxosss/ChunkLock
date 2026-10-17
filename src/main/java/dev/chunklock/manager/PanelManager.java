package dev.chunklock.manager;

import dev.chunklock.ChunkLockPlugin;
import dev.chunklock.config.Messages;
import dev.chunklock.config.Settings;
import dev.chunklock.manager.IslandManager.Plot;
import dev.chunklock.model.ChunkPos;
import dev.chunklock.model.Island;
import net.kyori.adventure.text.Component;
import org.bukkit.Color;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static dev.chunklock.config.Messages.ph;

/**
 * Cedulky u zamčených chunků (TextDisplay + ItemDisplay + Interaction).
 * Entity nejsou perzistentní - vznikají jen když je na ostrově hráč.
 */
public final class PanelManager {

    private static final class Panel {
        final ChunkPos rel;
        final List<Entity> entities = new ArrayList<>();
        final Map<Material, TextDisplay> counters = new LinkedHashMap<>();
        TextDisplay footer;
        TextDisplay moneyLine;
        Interaction hitbox;

        Panel(ChunkPos rel) {
            this.rel = rel;
        }
    }

    private final ChunkLockPlugin plugin;
    private final NamespacedKey panelKey;
    /** Značka na VŠECH entitách cedulek (hodnota = UUID majitele ostrova) - umožňuje najít a smazat sirotky. */
    private final NamespacedKey ownerKey;
    private final Map<UUID, List<Panel>> panels = new HashMap<>();
    private final Set<UUID> tracked = new HashSet<>();
    /** Ostrovy, kterým se zrovna nesmí vytvářet cedulky (probíhá reset). */
    private final Map<UUID, Integer> suspended = new HashMap<>();

    public PanelManager(ChunkLockPlugin plugin) {
        this.plugin = plugin;
        this.panelKey = new NamespacedKey(plugin, "panel");
        this.ownerKey = new NamespacedKey(plugin, "panel_owner");
    }

    public NamespacedKey key() {
        return panelKey;
    }

    // ---------------------------------------------------------------- životní cyklus

    /** Zahodí staré cedulky ostrova a vytvoří nové. */
    public void spawn(Island island) {
        remove(island.owner());
        if (suspended.containsKey(island.owner())) return;
        IslandManager im = plugin.islands();
        if (im.world() == null) return;
        List<Panel> list = new ArrayList<>();
        Deque<Runnable> pending = new ArrayDeque<>(); // další patra cedulek (pod sebou), vznikají postupně
        for (ChunkPos locked : im.lockedFrontier(island)) {
            for (int[] d : IslandManager.DIRS) {
                if (island.isUnlocked(locked.offset(d[0], d[1]))) {
                    build(island, locked, d, list, pending);
                }
            }
        }
        panels.put(island.owner(), list);
        World w = im.world();
        for (Player p : w.getPlayers()) syncVisibility(p, list);
        if (!pending.isEmpty()) buildStack(island.owner(), list, pending);
    }

    /** Postaví další patra cedulek po několika za tick (jinak by server při velkém počtu entit zaseknul). */
    private void buildStack(UUID owner, List<Panel> list, Deque<Runnable> pending) {
        final int perTick = Math.max(1, plugin.settings().stackPerTick);
        new BukkitRunnable() {
            @Override
            public void run() {
                if (panels.get(owner) != list) { // cedulky mezitím zrušeny / přegenerovány
                    cancel();
                    return;
                }
                for (int i = 0; i < perTick && !pending.isEmpty(); i++) {
                    try {
                        pending.poll().run();
                    } catch (RuntimeException ex) {
                        plugin.getLogger().log(java.util.logging.Level.WARNING, "Stavba patra cedulky selhala", ex);
                    }
                }
                if (pending.isEmpty()) cancel();
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    /** Řádek s cenou vidí jen hráči s permisí pro nákup za peníze. */
    private void syncVisibility(Player player, List<Panel> list) {
        boolean canSee = plugin.unlocks().canPay(player);
        for (Panel panel : list) {
            TextDisplay line = panel.moneyLine;
            if (line == null || !line.isValid()) continue;
            if (canSee) player.showEntity(plugin, line);
            else player.hideEntity(plugin, line);
        }
    }

    /** Zastaví tvorbu cedulek ostrova a smaže stávající (použito při resetu). */
    public void suspend(UUID owner) {
        suspended.merge(owner, 1, Integer::sum);
        remove(owner);
    }

    /** Povolí tvorbu cedulek znovu; vzniknou při dalším ticku nebo teleportu. */
    public void resume(UUID owner) {
        suspended.computeIfPresent(owner, (k, v) -> v <= 1 ? null : v - 1);
    }

    public void ensure(Island island) {
        if (suspended.containsKey(island.owner())) return;
        List<Panel> list = panels.get(island.owner());
        if (list == null || !valid(list)) spawn(island);
    }

    public void remove(UUID owner) {
        List<Panel> list = panels.remove(owner);
        if (list != null) {
            for (Panel p : list) {
                for (Entity e : p.entities) {
                    tracked.remove(e.getUniqueId());
                    e.remove(); // bez kontroly isValid - jinak by se zapomenuté entity nikdy nesmazaly
                }
            }
        }
        sweep(owner);
    }

    /** Smaže všechny entity cedulek daného majitele (null = všech), i ty, které si manager nepamatuje. */
    private void sweep(UUID owner) {
        World w = plugin.islands().world();
        if (w == null) return;
        String id = owner == null ? null : owner.toString();
        for (Entity e : w.getEntitiesByClasses(Display.class, Interaction.class)) {
            String v = e.getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING);
            if (v == null) continue;
            if (id == null || id.equals(v)) {
                tracked.remove(e.getUniqueId());
                e.remove();
            }
        }
    }

    /** Je to entita cedulky, kterou už nikdo nespravuje? (zbytek po pádu/reloadu/chybě) */
    public boolean isOrphan(Entity e) {
        return e.getPersistentDataContainer().has(ownerKey, PersistentDataType.STRING)
                && !tracked.contains(e.getUniqueId());
    }

    /** Úklid po startu pluginu. */
    public void sweepAll() {
        sweep(null);
    }

    private <T extends Entity> T add(Panel panel, Island island, T e) {
        e.getPersistentDataContainer().set(ownerKey, PersistentDataType.STRING, island.owner().toString());
        tracked.add(e.getUniqueId());
        panel.entities.add(e);
        return e;
    }

    public void removeAll() {
        for (UUID owner : new ArrayList<>(panels.keySet())) remove(owner);
        sweep(null);
    }

    /** Volá se periodicky: vytváří cedulky pro ostrovy s hráči a ruší ostatní. */
    public void tick() {
        IslandManager im = plugin.islands();
        World world = im.world();
        if (world == null) return;
        Set<UUID> present = new HashSet<>();
        for (Player p : world.getPlayers()) {
            Plot plot = im.plotAt(p.getLocation());
            if (plot == null) continue;
            present.add(plot.island().owner());
            ensure(plot.island());
            List<Panel> list = panels.get(plot.island().owner());
            if (list != null) syncVisibility(p, list);
        }
        for (UUID owner : new ArrayList<>(panels.keySet())) {
            if (!present.contains(owner)) remove(owner);
        }
    }

    private boolean valid(List<Panel> list) {
        for (Panel p : list) {
            if (p.hitbox == null || p.hitbox.isDead()) return false;
            for (Entity e : p.entities) {
                if (e.isDead()) return false;
            }
        }
        return true;
    }

    /** Aktualizuje počty u cedulek jednoho chunku. */
    public void refresh(Island island, ChunkPos rel) {
        List<Panel> list = panels.get(island.owner());
        if (list == null) return;
        List<Settings.Requirement> reqs = plugin.unlocks().requirements(island, rel);
        for (Panel p : list) {
            if (!p.rel.equals(rel)) continue;
            for (Settings.Requirement r : reqs) {
                TextDisplay d = p.counters.get(r.material());
                if (d != null && d.isValid()) d.text(counter(island, rel, r));
            }
            if (p.footer != null && p.footer.isValid()) p.footer.text(footer(island, rel));
            if (p.moneyLine != null && p.moneyLine.isValid()) p.moneyLine.text(moneyText(island, rel));
        }
    }

    // ---------------------------------------------------------------- stavba cedulky

    /** Spodní hranice pro patra cedulek: bedrock (dno světa u ostrovů z normálního světa). */
    private double bottomY(Island island, World w) {
        Settings s = plugin.settings();
        if (island.hasSource()) return w.getMinHeight();
        return Math.max(w.getMinHeight(), s.surfaceY - s.fillerDepth - s.stoneDepth - (s.bedrockBottom ? 1 : 0));
    }

    /**
     * Postaví cedulku u hrany zamčeného chunku (horní patro hned) a naplánuje stejné cedulky
     * pod ní každých panel.stack.spacing bloků až k bedrocku.
     */
    private void build(Island island, ChunkPos locked, int[] dir, List<Panel> list, Deque<Runnable> pending) {
        Settings s = plugin.settings();
        IslandManager im = plugin.islands();
        World w = im.world();

        double minX = (im.centerChunkX(island) + locked.x()) << 4;
        double minZ = (im.centerChunkZ(island) + locked.z()) << 4;
        double px;
        double pz;
        double tx;
        double tz;
        if (dir[0] != 0) { // soused na ose X, cedulka u jeho hrany
            px = dir[0] < 0 ? minX + s.panelOffset : minX + 16 - s.panelOffset;
            pz = minZ + 8;
            tx = 0;
            tz = 1;
        } else {
            pz = dir[1] < 0 ? minZ + s.panelOffset : minZ + 16 - s.panelOffset;
            px = minX + 8;
            tx = 1;
            tz = 0;
        }
        final double top = surfaceAt(island, w, dir, minX, minZ, px, pz) + 1 + s.panelHeight;
        // cedulka je natočená k odemčenému sousedovi (tam stojí hráči); 0 = jih, 90 = západ, 180 = sever, -90 = východ
        final float yaw = dir[1] > 0 ? 0f : dir[1] < 0 ? 180f : dir[0] < 0 ? 90f : -90f;

        list.add(buildAt(island, locked, w, px, pz, tx, tz, top, yaw));
        if (!s.stackEnabled) return;
        final double bottom = bottomY(island, w);
        final double step = Math.max(3.0, s.stackSpacing);
        final int extra = s.stackMaxLevels > 0 ? s.stackMaxLevels : Integer.MAX_VALUE;
        for (int level = 1; level <= extra; level++) {
            final double base = top - level * step;
            if (base < bottom) break;
            pending.add(() -> {
                Panel p = buildAt(island, locked, w, px, pz, tx, tz, base, yaw);
                list.add(p);
                for (Player pl : w.getPlayers()) syncVisibility(pl, List.of(p));
            });
        }
    }

    /** Jedno patro cedulky (titulek, itemy, počty, ceny a klikací hitbox) s dolním okrajem ve výšce base. */
    private Panel buildAt(Island island, ChunkPos locked, World w, double px, double pz, double tx, double tz,
                          double base, float yaw) {
        Settings s = plugin.settings();
        Panel panel = new Panel(locked);

        List<Settings.Requirement> items = plugin.unlocks().requirements(island, locked);
        int n = items.size();

        // titulek
        Messages msg = plugin.messages();
        add(panel, island, text(w, new Location(w, px, base + 2.6, pz), msg.get("panel.title"), s.titleScale, yaw));

        // itemy + počty
        for (int i = 0; i < n; i++) {
            Settings.Requirement r = items.get(i);
            double off = (i - (n - 1) / 2.0) * s.itemSpacing;
            double x = px + tx * off;
            double z = pz + tz * off;
            add(panel, island, item(w, new Location(w, x, base + 1.6, z), r.material(), yaw));
            TextDisplay counter = text(w, new Location(w, x, base + 0.9, z), counter(island, locked, r), 1.0f, yaw);
            add(panel, island, counter);
            panel.counters.put(r.material(), counter);
        }

        // spodní text
        panel.footer = text(w, new Location(w, px, base, pz), footer(island, locked), 1.0f, yaw);
        add(panel, island, panel.footer);
        if (plugin.unlocks().moneyAvailable(island)) {
            panel.moneyLine = text(w, new Location(w, px, base - 0.3, pz), moneyText(island, locked), 1.0f, yaw);
            add(panel, island, panel.moneyLine);
        }

        // klikací hitbox
        float width = (float) Math.max(2.5, n * s.itemSpacing + 0.5);
        Interaction hitbox = w.spawn(new Location(w, px, base - 0.4, pz), Interaction.class, in -> {
            in.setInteractionWidth(width);
            in.setInteractionHeight(3.8f);
            in.setResponsive(true);
            in.setPersistent(false);
            in.getPersistentDataContainer().set(panelKey, PersistentDataType.STRING,
                    island.owner() + ";" + locked.key());
        });
        panel.hitbox = hitbox;
        add(panel, island, hitbox);
        return panel;
    }

    /** Výška terénu u hrany odemčeného souseda (pro ostrovy z normálního světa), jinak pevné surface-y. */
    private double surfaceAt(Island island, World w, int[] dir, double minX, double minZ, double px, double pz) {
        if (!island.hasSource()) return plugin.settings().surfaceY;
        int best = w.getMinHeight();
        for (int off = -4; off <= 4; off += 4) {
            int x;
            int z;
            if (dir[0] != 0) {
                x = (int) Math.floor(dir[0] < 0 ? minX - 1 : minX + 16);
                z = (int) Math.floor(pz) + off;
            } else {
                x = (int) Math.floor(px) + off;
                z = (int) Math.floor(dir[1] < 0 ? minZ - 1 : minZ + 16);
            }
            best = Math.max(best, w.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES));
        }
        return best;
    }

    private Component counter(Island island, ChunkPos rel, Settings.Requirement r) {
        int current = Math.min(island.progress(rel, r.material()), r.amount());
        return plugin.messages().get("panel.counter", ph("current", current), ph("need", r.amount()));
    }

    private Component footer(Island island, ChunkPos rel) {
        Messages msg = plugin.messages();
        return msg.get("panel.footer-click");
    }

    private Component moneyText(Island island, ChunkPos rel) {
        UnlockService unlocks = plugin.unlocks();
        String price = unlocks.formatPrice(unlocks.price(island, rel));
        return plugin.messages().get("panel.footer-money", ph("price", price));
    }

    private Display.Billboard billboard() {
        return plugin.settings().panelFixed ? Display.Billboard.FIXED : Display.Billboard.VERTICAL;
    }

    private TextDisplay text(World w, Location loc, Component content, float scale, float yaw) {
        float viewRange = plugin.settings().viewRange;
        loc.setYaw(yaw);
        loc.setPitch(0f);
        return w.spawn(loc, TextDisplay.class, d -> {
            d.text(content);
            d.setBillboard(billboard());
            d.setPersistent(false);
            d.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
            d.setShadowed(true);
            d.setAlignment(TextDisplay.TextAlignment.CENTER);
            d.setLineWidth(400);
            d.setViewRange(viewRange);
            d.setTransformation(new Transformation(new Vector3f(), new Quaternionf(),
                    new Vector3f(scale, scale, scale), new Quaternionf()));
        });
    }

    private ItemDisplay item(World w, Location loc, Material material, float yaw) {
        float scale = plugin.settings().itemScale;
        float viewRange = plugin.settings().viewRange;
        loc.setYaw(yaw);
        loc.setPitch(0f);
        return w.spawn(loc, ItemDisplay.class, d -> {
            d.setItemStack(new ItemStack(material));
            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.GUI);
            d.setBillboard(billboard());
            d.setPersistent(false);
            d.setViewRange(viewRange);
            d.setTransformation(new Transformation(new Vector3f(), new Quaternionf(),
                    new Vector3f(scale, scale, scale), new Quaternionf()));
        });
    }
}
