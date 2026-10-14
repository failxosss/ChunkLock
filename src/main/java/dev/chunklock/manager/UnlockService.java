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
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
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

    /**
     * Požadavky konkrétního zamčeného chunku. Položky tieru fungují jako zásobník, ze kterého se
     * pro každý chunk deterministicky (podle ostrova + pozice chunku) vybere jiná kombinace a množství.
     */
    public List<Settings.Requirement> requirements(Island island, ChunkPos rel) {
        Settings.Tier tier = tierFor(island);
        List<Settings.Requirement> pool = tier.items();
        ConfigurationSection v = plugin.getConfig().getConfigurationSection("unlock.variation");
        if (v == null || !v.getBoolean("enabled", true) || pool.isEmpty()) return pool;

        long seed = mix(island.owner().getMostSignificantBits());
        seed = mix(seed ^ island.owner().getLeastSignificantBits());
        seed = mix(seed ^ rel.key());
        seed = mix(seed ^ tier.from());
        Random rnd = new Random(seed);
        rnd.nextLong(); // rozehřátí

        int min = Math.max(1, Math.min(v.getInt("min-items", pool.size()), pool.size()));
        int max = Math.max(min, Math.min(v.getInt("max-items", pool.size()), pool.size()));
        int count = min + rnd.nextInt(max - min + 1);

        List<Integer> idx = new ArrayList<>();
        for (int i = 0; i < pool.size(); i++) idx.add(i);
        Collections.shuffle(idx, rnd);
        List<Integer> chosen = new ArrayList<>(idx.subList(0, count));
        Collections.sort(chosen);

        int variance = Math.max(0, Math.min(90, v.getInt("amount-variance", 0)));
        List<Settings.Requirement> out = new ArrayList<>();
        for (int i : chosen) {
            Settings.Requirement base = pool.get(i);
            double factor = 1.0 + (rnd.nextDouble() * 2.0 - 1.0) * variance / 100.0;
            int amount = Math.max(1, (int) Math.round(base.amount() * factor));
            out.add(new Settings.Requirement(base.material(), amount));
        }
        return out;
    }

    /** SplitMix64 - rozptýlí podobná semínka, aby sousední chunky nedostaly skoro stejný náhodný výběr. */
    private static long mix(long z) {
        z += 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    public boolean isComplete(Island island, ChunkPos rel) {
        for (Settings.Requirement r : requirements(island, rel)) {
            if (remaining(island, rel, r) > 0) return false;
        }
        return true;
    }

    public double price(Island island, ChunkPos rel) {
        Settings.Tier tier = tierFor(island);
        List<Settings.Requirement> reqs = requirements(island, rel);
        double price = tier.money();
        if (plugin.settings().reduceByProgress && !reqs.isEmpty()) {
            int total = 0;
            int left = 0;
            for (Settings.Requirement r : reqs) {
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

    /** Smí hráč platit penězi? (permise z configu, prázdná = každý) */
    public boolean canPay(Player player) {
        String perm = plugin.getConfig().getString("unlock.money.permission", "").trim();
        return perm.isEmpty() || player.hasPermission(perm);
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

        StringBuilder summary = new StringBuilder();
        int totalTaken = 0;
        for (Settings.Requirement req : requirements(island, rel)) {
            int need = remaining(island, rel, req);
            if (need <= 0) continue;
            int taken = takeItems(player, req.material(), need);
            if (taken <= 0) continue;
            island.addProgress(rel, req.material(), taken);
            totalTaken += taken;
            if (summary.length() > 0) summary.append(", ");
            summary.append(taken).append("x ").append(pretty(req.material()));
        }

        boolean complete = isComplete(island, rel);
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
        if (!canPay(player)) {
            msg.send(player, "unlock.no-buy-permission");
            return;
        }
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
