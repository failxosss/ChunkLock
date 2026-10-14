package dev.chunklock.manager;

import dev.chunklock.ChunkLockPlugin;
import dev.chunklock.config.Messages;
import dev.chunklock.config.Settings;
import dev.chunklock.model.ChunkPos;
import dev.chunklock.model.Island;
import dev.chunklock.model.IslandPermission;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import static dev.chunklock.config.Messages.ph;

/** Logika odemykání chunků: odevzdávání itemů a placení penězi. */
public final class UnlockService {

    private final ChunkLockPlugin plugin;

    public UnlockService(ChunkLockPlugin plugin) {
        this.plugin = plugin;
    }

    public Settings.Tier tierFor(Island island) {
        return plugin.settings().tierFor(island.unlockedCount());
    }

    public int remaining(Island island, ChunkPos rel, Settings.Requirement req) {
        return Math.max(0, req.amount() - island.progress(rel, req.material()));
    }

    public boolean isComplete(Island island, ChunkPos rel, Settings.Tier tier) {
        for (Settings.Requirement r : tier.items()) {
            if (remaining(island, rel, r) > 0) return false;
        }
        return true;
    }

    public double price(Island island, ChunkPos rel) {
        Settings.Tier tier = tierFor(island);
        double price = tier.money();
        if (plugin.settings().reduceByProgress && !tier.items().isEmpty()) {
            int total = 0;
            int left = 0;
            for (Settings.Requirement r : tier.items()) {
                total += r.amount();
                left += remaining(island, rel, r);
            }
            if (total > 0) price = price * left / total;
        }
        return Math.round(price * 100.0) / 100.0;
    }

    public boolean moneyAvailable(Island island) {
        return plugin.settings().moneyEnabled
                && tierFor(island).money() > 0
                && plugin.economy() != null;
    }

    public String formatPrice(double price) {
        EconomyHook eco = plugin.economy();
        return eco != null ? eco.format(price) : String.valueOf(price);
    }

    // ---------------------------------------------------------------- akce

    private boolean check(Player player, Island island, ChunkPos rel) {
        Messages msg = plugin.messages();
        if (!island.isMember(player.getUniqueId())) {
            msg.send(player, "unlock.not-member");
            return false;
        }
        if (!island.hasPermission(player.getUniqueId(), IslandPermission.UNLOCK)) {
            msg.send(player, "unlock.no-permission");
            return false;
        }
        if (!plugin.islands().canUnlock(island, rel)) {
            msg.send(player, "unlock.not-allowed");
            return false;
        }
        return true;
    }

    /** Hráč klikl na cedulku - odevzdá itemy z inventáře. */
    public void deliver(Player player, Island island, ChunkPos rel) {
        if (!check(player, island, rel)) return;
        Messages msg = plugin.messages();
        Settings.Tier tier = tierFor(island);

        StringBuilder summary = new StringBuilder();
        int totalTaken = 0;
        for (Settings.Requirement req : tier.items()) {
            int need = remaining(island, rel, req);
            if (need <= 0) continue;
            int taken = takeItems(player, req.material(), need);
            if (taken <= 0) continue;
            island.addProgress(rel, req.material(), taken);
            totalTaken += taken;
            if (summary.length() > 0) summary.append(", ");
            summary.append(taken).append("x ").append(pretty(req.material()));
        }

        boolean complete = isComplete(island, rel, tier);
        if (totalTaken == 0 && !complete) {
            msg.send(player, "unlock.no-items");
            return;
        }
        if (totalTaken > 0) msg.send(player, "unlock.delivered", ph("items", summary));

        if (complete) {
            complete(player, island, rel);
        } else {
            plugin.storage().saveIsland(island);
            plugin.panels().refresh(island, rel);
        }
    }

    /** Hráč kliknul se shiftem - zaplatí zbytek penězi. */
    public void pay(Player player, Island island, ChunkPos rel) {
        if (!check(player, island, rel)) return;
        Messages msg = plugin.messages();
        if (!moneyAvailable(island)) {
            msg.send(player, "unlock.money-disabled");
            return;
        }
        EconomyHook eco = plugin.economy();
        double price = price(island, rel);
        if (price > 0) {
            if (!eco.has(player, price) || !eco.withdraw(player, price)) {
                msg.send(player, "unlock.not-enough-money", ph("price", formatPrice(price)));
                return;
            }
            msg.send(player, "unlock.paid", ph("price", formatPrice(price)));
        }
        complete(player, island, rel);
    }

    private void complete(Player player, Island island, ChunkPos rel) {
        plugin.islands().unlockChunk(island, rel);
        Set<UUID> ids = new HashSet<>(island.members().keySet());
        ids.add(island.owner());
        for (UUID id : ids) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                plugin.messages().send(p, "unlock.success", ph("x", rel.x()), ph("z", rel.z()));
            }
        }
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f);
    }

    private int takeItems(Player player, Material material, int amount) {
        boolean strict = plugin.settings().strictItems;
        ItemStack[] contents = player.getInventory().getStorageContents();
        int left = amount;
        for (int i = 0; i < contents.length && left > 0; i++) {
            ItemStack stack = contents[i];
            if (stack == null || stack.getType() != material) continue;
            if (strict && stack.hasItemMeta()) continue;
            int take = Math.min(left, stack.getAmount());
            if (take >= stack.getAmount()) {
                contents[i] = null;
            } else {
                stack.setAmount(stack.getAmount() - take);
            }
            left -= take;
        }
        player.getInventory().setStorageContents(contents);
        return amount - left;
    }

    public static String pretty(Material material) {
        String[] words = material.name().toLowerCase(Locale.ROOT).split("_");
        StringBuilder sb = new StringBuilder();
        for (String w : words) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return sb.toString();
    }
}
