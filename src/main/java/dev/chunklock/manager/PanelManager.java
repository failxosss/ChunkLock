package dev.chunklock.manager;

import dev.chunklock.ChunkLockPlugin;
import dev.chunklock.config.Messages;
import dev.chunklock.config.Settings;
import dev.chunklock.manager.IslandManager.Plot;
import dev.chunklock.model.ChunkPos;
import dev.chunklock.model.Dimension;
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
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
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
        /** Výška každé entity nad spodní hranou cedulky (stejné pořadí jako entities). */
        final List<Double> offsets = new ArrayList<>();
        /** Hráč, který cedulku vidí (každý hráč má vlastní, ostatním je skrytá). */
        Player viewer;
        World world;
        double base;
        double minBase;
        double maxBase;
        double px;
        double pz;
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
    private final Map<String, List<Panel>> panels = new HashMap<>();
    private final Set<UUID> tracked = new HashSet<>();
    /** Ostrovy, kterým se zrovna nesmí vytvářet cedulky (probíhá reset). */
    private final Map<String, Integer> suspended = new HashMap<>();

    /** Klíč ostrova napříč dimenzemi: dimenze;majitel. */
    private static String k(Island island) {
        return island.dimension().id() + ";" + island.owner();
    }

    public PanelManager(ChunkLockPlugin plugin) {
        this.plugin = plugin;
        this.panelKey = new NamespacedKey(plugin, "panel");
        this.ownerKey = new NamespacedKey(plugin, "panel_owner");
    }

    public NamespacedKey key() {
        return panelKey;
    }

    // ---------------------------------------------------------------- životní cyklus

    /** Zahodí staré cedulky ostrova a každému hráči, který na něm stojí, vytvoří nové (vlastní). */
    public void spawn(Island island) {
        remove(island);
        if (suspended.containsKey(k(island))) return;
        IslandManager im = plugin.islandsFor(island);
        if (im.world() == null) return;
        for (Player p : im.world().getPlayers()) {
            Plot plot = im.plotAt(p.getLocation());
            if (plot != null && plot.island() == island) buildFor(island, p);
        }
    }

    /** Postaví hráči cedulky u všech zamčených stěn ostrova. Vidí je jen on. */
    private void buildFor(Island island, Player viewer) {
        IslandManager im = plugin.islandsFor(island);
        List<Panel> list = panels.computeIfAbsent(k(island), x -> new ArrayList<>());
        for (ChunkPos locked : im.lockedFrontier(island)) {
            for (int[] d : IslandManager.DIRS) {
                if (island.isUnlocked(locked.offset(d[0], d[1]))) {
                    build(island, locked, d, list, viewer);
                }
            }
        }
    }

    private List<Panel> panelsOf(Island island, Player viewer) {
        List<Panel> out = new ArrayList<>();
        List<Panel> list = panels.get(k(island));
        if (list == null) return out;
        for (Panel p : list) if (p.viewer == viewer) out.add(p); // stejná instance (po novém připojení je hráč jiný objekt)
        return out;
    }

    /** Smaže cedulky vyhovující podmínce (entity i záznamy). */
    private void drop(List<Panel> list, java.util.function.Predicate<Panel> which) {
        java.util.Iterator<Panel> it = list.iterator();
        while (it.hasNext()) {
            Panel p = it.next();
            if (!which.test(p)) continue;
            for (Entity e : p.entities) {
                tracked.remove(e.getUniqueId());
                e.remove();
            }
            it.remove();
        }
    }

    private void ensureFor(Island island, Player player) {
        if (suspended.containsKey(k(island))) return;
        List<Panel> mine = panelsOf(island, player);
        if (mine.isEmpty() || !valid(mine)) {
            List<Panel> list = panels.get(k(island));
            if (list != null) drop(list, p -> p.viewer == player || p.viewer == null || !p.viewer.isOnline());
            buildFor(island, player);
        }
    }

    /** Zastaví tvorbu cedulek ostrova a smaže stávající (použito při resetu). */
    public void suspend(Island island) {
        suspended.merge(k(island), 1, Integer::sum);
        remove(island);
    }

    /** Povolí tvorbu cedulek znovu; vzniknou při dalším ticku nebo teleportu. */
    public void resume(Island island) {
        suspended.computeIfPresent(k(island), (key, v) -> v <= 1 ? null : v - 1);
    }

    public void ensure(Island island) {
        IslandManager im = plugin.islandsFor(island);
        if (im.world() == null) return;
        for (Player p : im.world().getPlayers()) {
            Plot plot = im.plotAt(p.getLocation());
            if (plot != null && plot.island() == island) ensureFor(island, p);
        }
    }

    public void remove(Island island) {
        removeKey(k(island), plugin.islandsFor(island).world());
    }

    private void removeKey(String key, World w) {
        List<Panel> list = panels.remove(key);
        if (list != null) {
            for (Panel p : list) {
                for (Entity e : p.entities) {
                    tracked.remove(e.getUniqueId());
                    e.remove(); // bez kontroly isValid - jinak by se zapomenuté entity nikdy nesmazaly
                }
            }
        }
        sweep(key, w);
    }

    /** Smaže všechny entity cedulek daného klíče (null = všech) ve světě, i ty, které si manager nepamatuje. */
    private void sweep(String id, World w) {
        if (w == null) return;
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
        for (IslandManager m : plugin.allIslandManagers()) sweep(null, m.world());
    }

    private <T extends Entity> T add(Panel panel, Island island, T e, double dy) {
        panel.offsets.add(dy);
        e.getPersistentDataContainer().set(ownerKey, PersistentDataType.STRING, k(island));
        tracked.add(e.getUniqueId());
        panel.entities.add(e);
        if (panel.viewer != null) panel.viewer.showEntity(plugin, e); // ostatním zůstává skrytá
        return e;
    }

    public void removeAll() {
        sweepAll();
        for (String key : new ArrayList<>(panels.keySet())) removeKey(key, null);
    }

    /** Volá se periodicky: vytváří cedulky hráčům na ostrovech a ruší je těm, kdo odešli. */
    public void tick() {
        for (IslandManager im : plugin.allIslandManagers()) {
            World world = im.world();
            if (world == null) continue;
            String prefix = im.dimension().id() + ";";
            Map<String, Set<Player>> present = new HashMap<>();
            for (Player p : world.getPlayers()) {
                Plot plot = im.plotAt(p.getLocation());
                if (plot == null) continue;
                present.computeIfAbsent(k(plot.island()), x -> new HashSet<>()).add(p);
                ensureFor(plot.island(), p);
            }
            for (String key : new ArrayList<>(panels.keySet())) {
                if (!key.startsWith(prefix)) continue;
                Set<Player> here = present.getOrDefault(key, Set.of());
                List<Panel> list = panels.get(key);
                drop(list, p -> p.viewer == null || !here.contains(p.viewer));
                if (list.isEmpty()) {
                    panels.remove(key);
                    sweep(key, world); // pojistka proti zapomenutým entitám
                }
            }
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
        List<Panel> list = panels.get(k(island));
        if (list == null) return;
        List<Settings.Requirement> reqs = plugin.unlocks().requirements(island, rel);
        for (Panel p : list) {
            if (!p.rel.equals(rel)) continue;
            for (Settings.Requirement r : reqs) {
                TextDisplay d = p.counters.get(r.material());
                if (d != null && d.isValid()) d.text(counter(island, rel, r));
            }
            if (p.footer != null && p.footer.isValid()) p.footer.text(footer(island, rel));
            if (p.moneyLine != null && p.moneyLine.isValid()) p.moneyLine.text(moneyText(island, rel, p.viewer));
        }
    }

    // ---------------------------------------------------------------- stavba cedulky

    /** Spodní hranice pro patra cedulek: bedrock (dno světa u ostrovů z normálního světa). */
    private double bottomY(Island island, World w) {
        Settings s = plugin.settings();
        if (island.dimension() != Dimension.OVERWORLD || island.hasSource()) return w.getMinHeight();
        return Math.max(w.getMinHeight(), s.surfaceY - s.fillerDepth - s.stoneDepth - (s.bedrockBottom ? 1 : 0));
    }

    /**
     * Postaví cedulku u hrany zamčeného chunku. Je jen jedna na stěnu a {@link #follow()} ji posouvá nahoru a dolů
     * podle výšky nejbližšího hráče.
     */
    private void build(Island island, ChunkPos locked, int[] dir, List<Panel> list, Player viewer) {
        Settings s = plugin.settings();
        IslandManager im = plugin.islandsFor(island);
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

        Panel p = buildAt(island, locked, w, px, pz, tx, tz, top, yaw, viewer);
        p.world = w;
        p.base = top;
        p.px = px;
        p.pz = pz;
        p.minBase = bottomY(island, w) + 1;
        p.maxBase = w.getMaxHeight() - 4;
        list.add(p);
    }

    /**
     * Volá se často: posune každou cedulku svisle za jejím hráčem (zůstává na stěně, mění jen výšku).
     * Každý hráč má vlastní cedulky, takže každému jezdí podle jeho výšky. Displeje se posouvají plynule
     * (teleport-duration), hitbox skočí hned.
     */
    public void follow() {
        final double panelHeight = plugin.settings().panelHeight;
        for (List<Panel> list : panels.values()) {
            for (Panel p : list) {
                Player viewer = p.viewer;
                if (viewer == null || !viewer.isOnline() || p.world == null || viewer.getWorld() != p.world) continue;
                if (p.hitbox == null || p.hitbox.isDead()) continue;
                double target = Math.max(p.minBase, Math.min(p.maxBase, viewer.getLocation().getY() + panelHeight));
                if (Math.abs(target - p.base) < 0.75) continue; // drobné pohyby (skákání) ignorujeme
                p.base = target;
                for (int i = 0; i < p.entities.size(); i++) {
                    Entity e = p.entities.get(i);
                    if (e.isDead()) continue;
                    Location l = e.getLocation();
                    l.setY(target + p.offsets.get(i));
                    e.teleport(l);
                }
            }
        }
    }

    /** Jedno patro cedulky (titulek, itemy, počty, ceny a klikací hitbox) s dolním okrajem ve výšce base. */
    private Panel buildAt(Island island, ChunkPos locked, World w, double px, double pz, double tx, double tz,
                          double base, float yaw, Player viewer) {
        Settings s = plugin.settings();
        Panel panel = new Panel(locked);
        panel.viewer = viewer;

        List<Settings.Requirement> items = plugin.unlocks().requirements(island, locked);
        int n = items.size();

        // titulek
        Messages msg = plugin.messages();
        add(panel, island, text(w, new Location(w, px, base + 2.6, pz), msg.get("panel.title"), s.titleScale, yaw), 2.6);

        // itemy + počty
        for (int i = 0; i < n; i++) {
            Settings.Requirement r = items.get(i);
            double off = (i - (n - 1) / 2.0) * s.itemSpacing;
            double x = px + tx * off;
            double z = pz + tz * off;
            add(panel, island, item(w, new Location(w, x, base + 1.6, z), r.material(), yaw), 1.6);
            TextDisplay counter = text(w, new Location(w, x, base + 0.9, z), counter(island, locked, r), 1.0f, yaw);
            add(panel, island, counter, 0.9);
            panel.counters.put(r.material(), counter);
        }

        // spodní text
        panel.footer = text(w, new Location(w, px, base, pz), footer(island, locked), 1.0f, yaw);
        add(panel, island, panel.footer, 0.0);
        if (plugin.unlocks().moneyAvailable(island) && plugin.unlocks().canPay(viewer)) {
            panel.moneyLine = text(w, new Location(w, px, base - 0.3, pz), moneyText(island, locked, viewer), 1.0f, yaw);
            add(panel, island, panel.moneyLine, -0.3);
        }

        // klikací hitbox
        float width = (float) Math.max(2.5, n * s.itemSpacing + 0.5);
        Interaction hitbox = w.spawn(new Location(w, px, base - 0.4, pz), Interaction.class, in -> {
            in.setInteractionWidth(width);
            in.setInteractionHeight(3.8f);
            in.setResponsive(true);
            in.setVisibleByDefault(false);
            in.setPersistent(false);
            in.getPersistentDataContainer().set(panelKey, PersistentDataType.STRING,
                    island.dimension().id() + ";" + island.owner() + ";" + locked.key());
        });
        panel.hitbox = hitbox;
        add(panel, island, hitbox, -0.4);
        return panel;
    }

    /** Výška terénu u hrany odemčeného souseda (pro ostrovy z normálního světa), jinak pevné surface-y. */
    private double surfaceAt(Island island, World w, int[] dir, double minX, double minZ, double px, double pz) {
        if (island.dimension() == Dimension.NETHER) return netherSurface(island, w, dir, minX, minZ, px, pz);
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
        if (island.dimension() == Dimension.END && island.hasRefY() && best <= w.getMinHeight()) return island.refY();
        return best;
    }

    /** Nether nemá povrch: cedulka stojí na podlaze nejblíže výšce startovního chunku. */
    private double netherSurface(Island island, World w, int[] dir, double minX, double minZ, double px, double pz) {
        int ref = island.hasRefY() ? island.refY() : 64;
        int best = Integer.MIN_VALUE;
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
            int feet = TerrainCopier.findFloorY(w, x, z, ref + 24, ref - 24);
            if (feet != Integer.MIN_VALUE) best = Math.max(best, feet - 1);
        }
        return best == Integer.MIN_VALUE ? ref - 1 : best;
    }

    private Component counter(Island island, ChunkPos rel, Settings.Requirement r) {
        int current = Math.min(island.progress(rel, r.material()), r.amount());
        return plugin.messages().get("panel.counter", ph("current", current), ph("need", r.amount()));
    }

    private Component footer(Island island, ChunkPos rel) {
        Messages msg = plugin.messages();
        return msg.get("panel.footer-click");
    }

    private Component moneyText(Island island, ChunkPos rel, Player viewer) {
        UnlockService unlocks = plugin.unlocks();
        double price = viewer != null ? unlocks.price(viewer, island, rel) : unlocks.price(island, rel);
        return plugin.messages().get("panel.footer-money", ph("price", unlocks.formatPrice(price)));
    }

    private Display.Billboard billboard() {
        return plugin.settings().panelFixed ? Display.Billboard.FIXED : Display.Billboard.VERTICAL;
    }

    private TextDisplay text(World w, Location loc, Component content, float scale, float yaw) {
        float viewRange = plugin.settings().viewRange;
        loc.setYaw(yaw);
        loc.setPitch(0f);
        return w.spawn(loc, TextDisplay.class, d -> {
            d.setVisibleByDefault(false);
            d.text(content);
            d.setBillboard(billboard());
            d.setPersistent(false);
            d.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
            d.setShadowed(true);
            d.setAlignment(TextDisplay.TextAlignment.CENTER);
            d.setLineWidth(400);
            d.setViewRange(viewRange);
            d.setTeleportDuration(4); // plynulý pohyb nahoru a dolů za hráčem
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
            d.setVisibleByDefault(false);
            d.setItemStack(new ItemStack(material));
            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.GUI);
            d.setBillboard(billboard());
            d.setPersistent(false);
            d.setViewRange(viewRange);
            d.setTeleportDuration(4);
            d.setTransformation(new Transformation(new Vector3f(), new Quaternionf(),
                    new Vector3f(scale, scale, scale), new Quaternionf()));
        });
    }
}
