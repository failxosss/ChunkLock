package dev.chunklock.manager;

import dev.chunklock.ChunkLockPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static dev.chunklock.config.Messages.ph;

/**
 * Odemykací svitek: item s počtem "nábojů". Klik na cedulku zamčeného chunku s ním v ruce odemkne chunk zdarma
 * a ubere jeden náboj (při nule item zmizí). Počty jsou uložené přímo v itemu.
 */
public final class TokenService {

    private final ChunkLockPlugin plugin;
    private final NamespacedKey leftKey;
    private final NamespacedKey maxKey;
    /** Unikátní id - svitky se tak nikdy nesloučí do jednoho stacku (každý má vlastní počet nábojů). */
    private final NamespacedKey idKey;

    public TokenService(ChunkLockPlugin plugin) {
        this.plugin = plugin;
        this.leftKey = new NamespacedKey(plugin, "token_left");
        this.maxKey = new NamespacedKey(plugin, "token_max");
        this.idKey = new NamespacedKey(plugin, "token_id");
    }

    private Material material() {
        Material m = Material.matchMaterial(plugin.getConfig().getString("unlock-token.material", "NETHER_STAR"));
        if (m == null || m.isAir() || !m.isItem() || m.isBlock()) return Material.NETHER_STAR;
        return m;
    }

    public boolean isToken(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(leftKey, PersistentDataType.INTEGER);
    }

    /** Kolik nábojů svitku zbývá (0 když to není svitek). */
    public int remaining(ItemStack item) {
        if (!isToken(item)) return 0;
        Integer left = item.getItemMeta().getPersistentDataContainer().get(leftKey, PersistentDataType.INTEGER);
        return left == null ? 0 : Math.max(0, left);
    }

    /** Nový svitek na "charges" odemčení. */
    public ItemStack create(int charges) {
        ItemStack item = new ItemStack(material());
        ItemMeta meta = item.getItemMeta();
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(leftKey, PersistentDataType.INTEGER, charges);
        pdc.set(maxKey, PersistentDataType.INTEGER, charges);
        pdc.set(idKey, PersistentDataType.STRING, UUID.randomUUID().toString());
        if (plugin.getConfig().getBoolean("unlock-token.glow", true)) meta.setEnchantmentGlintOverride(true);
        render(meta, charges, charges);
        item.setItemMeta(meta);
        return item;
    }

    private void render(ItemMeta meta, int left, int max) {
        meta.displayName(plain(plugin.messages().get("token.name")));
        List<Component> lore = new ArrayList<>();
        for (Component c : plugin.messages().list("token.lore", ph("left", left), ph("max", max))) {
            lore.add(plain(c));
        }
        meta.lore(lore);
    }

    private static Component plain(Component c) {
        return c.decoration(TextDecoration.ITALIC, false);
    }

    /**
     * Ubere jeden náboj ze svitku v hlavní ruce hráče (při nule svitek zmizí).
     * @return kolik nábojů zbývá, nebo -1 když v ruce svitek není
     */
    public int consume(Player player) {
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (!isToken(hand)) return -1;
        int left = remaining(hand) - 1;
        if (left <= 0) {
            player.getInventory().setItemInMainHand(null);
            return 0;
        }
        ItemMeta meta = hand.getItemMeta();
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(leftKey, PersistentDataType.INTEGER, left);
        Integer max = pdc.get(maxKey, PersistentDataType.INTEGER);
        render(meta, left, max == null ? left : Math.max(max, left));
        hand.setItemMeta(meta);
        player.getInventory().setItemInMainHand(hand);
        return left;
    }
}
