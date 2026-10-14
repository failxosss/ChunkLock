package dev.chunklock.manager;

import dev.chunklock.ChunkLockPlugin;
import dev.chunklock.config.Messages;
import dev.chunklock.config.Settings;
import dev.chunklock.manager.IslandManager.Plot;
import dev.chunklock.model.ChunkPos;
import dev.chunklock.model.Island;
import net.kyori.adventure.text.Component;
import org.bukkit.Color;
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
        final Map<Material, TextDisplay> counters = new LinkedHashMap<>();
        TextDisplay footer;
        Interaction hitbox;

        Panel(ChunkPos rel) {
            this.rel = rel;
        }
    }

    private final ChunkLockPlugin plugin;
    private final NamespacedKey panelKey;
    private final Map<UUID, List<Panel>> panels = new HashMap<>();

    public PanelManager(ChunkLockPlugin plugin) {
        this.plugin = plugin;
        this.panelKey = new NamespacedKey(plugin, "panel");
    }

    public NamespacedKey key() {
        return panelKey;
    }

    // ---------------------------------------------------------------- životní cyklus

    /** Zahodí staré cedulky ostrova a vytvoří nové. */
    public void spawn(Island island) {
        remove(island.owner());
        IslandManager im = plugin.islands();
        if (im.world() == null) return;
        List<Panel> list = new ArrayList<>();
        for (ChunkPos locked : im.lockedFrontier(island)) {
            for (int[] d : IslandManager.DIRS) {
                if (island.isUnlocked(locked.offset(d[0], d[1]))) {
                    list.add(build(island, locked, d));
                }
            }
        }
        panels.put(island.owner(), list);
    }

    public void ensure(Island island) {
        List<Panel> list = panels.get(island.owner());
        if (list == null || !valid(list)) spawn(island);
    }

    public void remove(UUID owner) {
        List<Panel> list = panels.remove(owner);
        if (list == null) return;
        for (Panel p : list) {
            for (Entity e : p.entities) {
                if (e.isValid()) e.remove();
            }
        }
    }

    public void removeAll() {
        for (UUID owner : new ArrayList<>(panels.keySet())) remove(owner);
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
        }
        for (UUID owner : new ArrayList<>(panels.keySet())) {
            if (!present.contains(owner)) remove(owner);
        }
    }

    private boolean valid(List<Panel> list) {
        for (Panel p : list) {
            if (p.hitbox == null || !p.hitbox.isValid()) return false;
        }
        return true;
    }

    /** Aktualizuje počty u cedulek jednoho chunku. */
    public void refresh(Island island, ChunkPos rel) {
        List<Panel> list = panels.get(island.owner());
        if (list == null) return;
        Settings.Tier tier = plugin.unlocks().tierFor(island);
        for (Panel p : list) {
            if (!p.rel.equals(rel)) continue;
            for (Settings.Requirement r : tier.items()) {
                TextDisplay d = p.counters.get(r.material());
                if (d != null && d.isValid()) d.text(counter(island, rel, r));
            }
            if (p.footer != null && p.footer.isValid()) p.footer.text(footer(island, rel));
        }
    }

    // ---------------------------------------------------------------- stavba cedulky

    private Panel build(Island island, ChunkPos locked, int[] dir) {
        Settings s = plugin.settings();
        IslandManager im = plugin.islands();
        World w = im.world();
        Panel panel = new Panel(locked);

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
        double base = s.surfaceY + 1 + s.panelHeight;

        Settings.Tier tier = plugin.unlocks().tierFor(island);
        List<Settings.Requirement> items = tier.items();
        int n = items.size();

        // titulek
        Messages msg = plugin.messages();
        panel.entities.add(text(w, new Location(w, px, base + 2.6, pz), msg.get("panel.title"), s.titleScale));

        // itemy + počty
        for (int i = 0; i < n; i++) {
            Settings.Requirement r = items.get(i);
            double off = (i - (n - 1) / 2.0) * s.itemSpacing;
            double x = px + tx * off;
            double z = pz + tz * off;
            panel.entities.add(item(w, new Location(w, x, base + 1.6, z), r.material()));
            TextDisplay counter = text(w, new Location(w, x, base + 0.9, z), counter(island, locked, r), 1.0f);
            panel.entities.add(counter);
            panel.counters.put(r.material(), counter);
        }

        // spodní text
        panel.footer = text(w, new Location(w, px, base, pz), footer(island, locked), 1.0f);
        panel.entities.add(panel.footer);

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
        panel.entities.add(hitbox);
        return panel;
    }

    private Component counter(Island island, ChunkPos rel, Settings.Requirement r) {
        int current = Math.min(island.progress(rel, r.material()), r.amount());
        return plugin.messages().get("panel.counter", ph("current", current), ph("need", r.amount()));
    }

    private Component footer(Island island, ChunkPos rel) {
        Messages msg = plugin.messages();
        Component c = msg.get("panel.footer-click");
        UnlockService unlocks = plugin.unlocks();
        if (unlocks.moneyAvailable(island)) {
            String price = unlocks.formatPrice(unlocks.price(island, rel));
            c = c.append(Component.newline()).append(msg.get("panel.footer-money", ph("price", price)));
        }
        return c;
    }

    private TextDisplay text(World w, Location loc, Component content, float scale) {
        float viewRange = plugin.settings().viewRange;
        return w.spawn(loc, TextDisplay.class, d -> {
            d.text(content);
            d.setBillboard(Display.Billboard.VERTICAL);
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

    private ItemDisplay item(World w, Location loc, Material material) {
        float scale = plugin.settings().itemScale;
        float viewRange = plugin.settings().viewRange;
        return w.spawn(loc, ItemDisplay.class, d -> {
            d.setItemStack(new ItemStack(material));
            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.GUI);
            d.setBillboard(Display.Billboard.VERTICAL);
            d.setPersistent(false);
            d.setViewRange(viewRange);
            d.setTransformation(new Transformation(new Vector3f(), new Quaternionf(),
                    new Vector3f(scale, scale, scale), new Quaternionf()));
        });
    }
}
