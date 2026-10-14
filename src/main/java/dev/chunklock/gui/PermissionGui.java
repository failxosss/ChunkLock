package dev.chunklock.gui;

import dev.chunklock.ChunkLockPlugin;
import dev.chunklock.config.Messages;
import dev.chunklock.model.Island;
import dev.chunklock.model.IslandPermission;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static dev.chunklock.config.Messages.ph;

/** Menu pro nastavení oprávnění člena. Klik na ikonu = přepnutí. */
public final class PermissionGui implements InventoryHolder {

    private static final int[] SLOTS = {10, 11, 12, 13, 14, 15, 16};

    private final ChunkLockPlugin plugin;
    private final Island island;
    private final UUID target;
    private final Inventory inventory;

    public PermissionGui(ChunkLockPlugin plugin, Island island, UUID target) {
        this.plugin = plugin;
        this.island = island;
        this.target = target;
        Island.Member member = island.members().get(target);
        String name = member != null ? member.name() : "?";
        this.inventory = Bukkit.createInventory(this, 27, plugin.messages().get("gui.title", ph("player", name)));
        render();
    }

    public void open(Player player) {
        player.openInventory(inventory);
    }

    private void render() {
        ItemStack filler = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta fm = filler.getItemMeta();
        fm.displayName(Component.empty());
        filler.setItemMeta(fm);
        for (int i = 0; i < inventory.getSize(); i++) inventory.setItem(i, filler);

        Island.Member member = island.members().get(target);
        if (member == null) return;
        Messages msg = plugin.messages();
        IslandPermission[] perms = IslandPermission.values();
        for (int i = 0; i < perms.length && i < SLOTS.length; i++) {
            IslandPermission perm = perms[i];
            boolean enabled = member.permissions().contains(perm);

            ItemStack stack = new ItemStack(perm.icon());
            ItemMeta meta = stack.getItemMeta();
            meta.displayName(plain(msg.get("perm." + perm.id() + ".name")).color(NamedTextColor.GOLD));
            List<Component> lore = new ArrayList<>();
            lore.add(plain(msg.get("perm." + perm.id() + ".desc")).color(NamedTextColor.GRAY));
            lore.add(Component.empty());
            lore.add(plain(msg.get(enabled ? "gui.allowed" : "gui.denied")));
            lore.add(plain(msg.get("gui.click-hint")));
            meta.lore(lore);
            meta.setEnchantmentGlintOverride(enabled);
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            stack.setItemMeta(meta);
            inventory.setItem(SLOTS[i], stack);
        }
    }

    private static Component plain(Component c) {
        return c.decoration(TextDecoration.ITALIC, false);
    }

    public void click(Player player, int slot) {
        if (!island.owner().equals(player.getUniqueId())) return;
        IslandPermission[] perms = IslandPermission.values();
        for (int i = 0; i < perms.length && i < SLOTS.length; i++) {
            if (SLOTS[i] == slot) {
                island.togglePermission(target, perms[i]);
                plugin.storage().saveIsland(island);
                render();
                return;
            }
        }
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }
}
