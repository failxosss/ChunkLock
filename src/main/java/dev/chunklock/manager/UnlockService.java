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
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import static dev.chunklock.config.Messages.ph;

/** Logika odemykání chunků: odevzdávání itemů a placení penězi. */
public final class UnlockService {

    private final ChunkLockPlugin plugin;
    private final TokenService tokens;

    public UnlockService(ChunkLockPlugin plugin) {
        this.plugin = plugin;
        this.tokens = new TokenService(plugin);
    }

    public TokenService tokens() {
        return tokens;
    }

    public Settings.Tier tierFor(Island island) {
        return plugin.settings().tierFor(island.unlockedCount());
    }

    public int remaining(Island island, ChunkPos rel, Settings.Requirement req) {
        return Math.max(0, req.amount() - island.progress(rel, req.material()));
    }

    /**
     * Pevné požadavky (unlock.mode: TIERS). Položky tieru fungují jako zásobník, ze kterého se
     * pro každý chunk deterministicky (podle ostrova + pozice chunku) vybere jiná kombinace a množství.
     */
    private List<Settings.Requirement> tierRequirements(Island island, ChunkPos rel) {
        List<Settings.Requirement> base = tierBase(island, rel);
        double mult = distanceMultiplier(rel);
        if (mult == 1.0) return base;
        List<Settings.Requirement> out = new ArrayList<>(base.size());
        for (Settings.Requirement r : base) {
            out.add(new Settings.Requirement(r.material(), Math.max(1, (int) Math.round(r.amount() * mult))));
        }
        return out;
    }

    /**
     * Obtížnost podle vzdálenosti od startovního chunku: 1.0 u nejbližších chunků, pak roste o per-chunk %
     * za každý další chunk vzdálenosti, nejvýš o max-bonus %.
     */
    public double distanceMultiplier(ChunkPos rel) {
        Settings s = plugin.settings();
        if (!s.distanceEnabled) return 1.0;
        int dist = s.distanceManhattan ? Math.abs(rel.x()) + Math.abs(rel.z()) : rel.distance();
        double bonus = Math.min(s.distanceMax, s.distancePerChunk * Math.max(0, dist - 1));
        return 1.0 + bonus / 100.0;
    }

    private List<Settings.Requirement> tierBase(Island island, ChunkPos rel) {
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

    /**
     * Požadavky konkrétního zamčeného chunku.
     * Režim CHUNK: počítají se ze surovin, které jde získat v sousedním odemčeném chunku (viz computeFromChunks),
     * a po prvním spočítání se drží u ostrova, aby se čísla na cedulce nepřepisovala.
     * Režim TIERS: pevné požadavky z config.yml.
     */
    public List<Settings.Requirement> requirements(Island island, ChunkPos rel) {
        if (!plugin.settings().chunkMode) return tierRequirements(island, rel);

        Map<Material, Integer> cached = island.required(rel);
        if (cached == null) {
            List<Settings.Requirement> fresh = computeFromChunks(island, rel);
            if (fresh == null || fresh.isEmpty()) return tierRequirements(island, rel); // nemáme z čeho počítat
            cached = new LinkedHashMap<>();
            for (Settings.Requirement r : fresh) cached.put(r.material(), r.amount());
            island.setRequired(rel, cached);
        }
        List<Settings.Requirement> out = new ArrayList<>(cached.size());
        for (Map.Entry<Material, Integer> e : cached.entrySet()) {
            out.add(new Settings.Requirement(e.getKey(), e.getValue()));
        }
        return out;
    }

    private record Candidate(Settings.ResourceRule rule, int pool) {}

    /**
     * Kolik které suroviny jde získat v sousedních odemčených chunkách zamčeného chunku. U každé suroviny se bere
     * ten nejbohatší soused (ne součet) - požadavek tak vždy vyjde z jednoho konkrétního chunku.
     * Vrací null, když chunk nemá žádného odemčeného souseda.
     */
    public Map<Settings.ResourceRule, Integer> pools(Island island, ChunkPos rel) {
        List<Map<Material, Integer>> neighbours = new ArrayList<>();
        for (int[] d : IslandManager.DIRS) {
            ChunkPos n = rel.offset(d[0], d[1]);
            if (island.isUnlocked(n)) neighbours.add(plugin.resources().get(island, n));
        }
        if (neighbours.isEmpty()) return null;

        Map<Settings.ResourceRule, Integer> out = new LinkedHashMap<>();
        for (Settings.ResourceRule rule : plugin.settings().resourceRules) {
            int best = 0;
            for (Map<Material, Integer> counts : neighbours) {
                double sum = 0;
                for (Material block : rule.blocks()) sum += counts.getOrDefault(block, 0) * rule.perBlock();
                best = Math.max(best, (int) Math.floor(sum));
            }
            out.put(rule, best);
        }
        return out;
    }

    /** Kolik daného itemu jde získat v sousedním chunku (pro admin přehled); 0 když nic / neznámé. */
    public int available(Island island, ChunkPos rel, Material item) {
        Map<Settings.ResourceRule, Integer> pools = pools(island, rel);
        if (pools == null) return 0;
        int best = 0;
        for (Map.Entry<Settings.ResourceRule, Integer> e : pools.entrySet()) {
            if (e.getKey().drop() == item) best = Math.max(best, e.getValue());
        }
        return best;
    }

    /**
     * Vybere požadavky tak, aby se daly splnit těžbou v sousedním chunku:
     *  - jen suroviny, kterých tam je aspoň min-pool, a které už jsou "odemčené" (from),
     *  - množství = podíl ze zásoby (share roste s počtem odemčených chunků), nikdy víc než max-share-per-item,
     *  - první položka je vždy "jádrová" (ruda / dřevo), další se losují podle váhy.
     * Výběr je deterministický (ostrov + pozice chunku). Vrací null, když chunk nemá odemčeného souseda.
     */
    private List<Settings.Requirement> computeFromChunks(Island island, ChunkPos rel) {
        Settings s = plugin.settings();
        Map<Settings.ResourceRule, Integer> pools = pools(island, rel);
        if (pools == null) return null;

        int unlocked = island.unlockedCount();
        double share = Math.min(s.shareMax, s.shareStart + s.sharePerChunk * Math.max(0, unlocked - 1)) / 100.0;

        long seed = mix(island.owner().getMostSignificantBits());
        seed = mix(seed ^ island.owner().getLeastSignificantBits());
        seed = mix(seed ^ rel.key());
        Random rnd = new Random(seed);
        rnd.nextLong(); // rozehřátí

        List<Candidate> eligible = new ArrayList<>();
        List<Candidate> rest = new ArrayList<>(); // záloha pro chudé chunky (ignoruje from a min-pool)
        for (Map.Entry<Settings.ResourceRule, Integer> e : pools.entrySet()) {
            Settings.ResourceRule rule = e.getKey();
            int pool = e.getValue();
            if (pool < 1) continue;
            Candidate c = new Candidate(rule, pool);
            if (rule.from() <= unlocked && pool >= rule.minPool()) eligible.add(c);
            else rest.add(c);
        }

        int count = s.chunkMinItems + rnd.nextInt(s.chunkMaxItems - s.chunkMinItems + 1);
        List<Candidate> picked = new ArrayList<>();

        // 1) první položka z jádrových surovin (rudy, dřevo), aby chunk nebyl jen "doneste hlínu"
        List<Candidate> core = new ArrayList<>();
        for (Candidate c : eligible) if (c.rule().core()) core.add(c);
        if (!core.isEmpty()) {
            Candidate first = drawWeighted(core, rnd);
            picked.add(first);
            eligible.remove(first);
        }
        // 2) zbytek podle váhy
        while (picked.size() < count && !eligible.isEmpty()) {
            picked.add(drawWeighted(eligible, rnd));
        }
        // 3) chudý chunek: doplníme nejbohatšími zbývajícími surovinami, ať je požadavků aspoň min-items
        rest.sort((a, b) -> Integer.compare(b.pool(), a.pool()));
        for (Candidate c : rest) {
            if (picked.size() >= s.chunkMinItems) break;
            picked.add(c);
        }

        List<Settings.ResourceRule> order = s.resourceRules;
        picked.sort(Comparator.comparingInt(c -> order.indexOf(c.rule())));

        double variance = s.shareVariance / 100.0;
        double distMult = distanceMultiplier(rel);
        Map<Material, Integer> out = new LinkedHashMap<>();
        for (Candidate c : picked) {
            double factor = 1.0 + (rnd.nextDouble() * 2.0 - 1.0) * variance;
            int cap = Math.max(1, (int) Math.floor(c.pool() * s.maxSharePerItem / 100.0));
            int amount = (int) Math.round(c.pool() * share * factor * distMult);
            amount = Math.max(amount, s.chunkMinAmount);
            amount = Math.min(amount, Math.min(c.rule().max(), cap));
            amount = Math.min(amount, c.pool());
            if (amount < 1) continue;
            out.merge(c.rule().drop(), amount, Math::max);
        }

        // už odevzdané itemy hráči nepropadnou (starší ostrovy / změna configu): zůstanou jako splněné
        for (Map.Entry<Material, Integer> e : island.progressOf(rel).entrySet()) {
            if (e.getValue() > 0 && !out.containsKey(e.getKey())) out.put(e.getKey(), e.getValue());
        }

        List<Settings.Requirement> result = new ArrayList<>(out.size());
        for (Map.Entry<Material, Integer> e : out.entrySet()) {
            result.add(new Settings.Requirement(e.getKey(), e.getValue()));
        }
        return result;
    }

    /** Vylosuje (a odebere ze seznamu) kandidáta podle váhy. */
    private static Candidate drawWeighted(List<Candidate> list, Random rnd) {
        int total = 0;
        for (Candidate c : list) total += c.rule().weight();
        int r = rnd.nextInt(Math.max(1, total));
        for (Iterator<Candidate> it = list.iterator(); it.hasNext(); ) {
            Candidate c = it.next();
            r -= c.rule().weight();
            if (r < 0) {
                it.remove();
                return c;
            }
        }
        return list.remove(list.size() - 1);
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
        price *= distanceMoney(rel);
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

    private double distanceMoney(ChunkPos rel) {
        return plugin.settings().distanceMoney ? distanceMultiplier(rel) : 1.0;
    }

    /** Sleva v % pro hráče: nejvyšší z unlock.money.discounts, na kterou má permisi. */
    public double discountPercent(Player player) {
        double best = 0;
        for (Map.Entry<String, Double> e : plugin.settings().moneyDiscounts.entrySet()) {
            if (e.getValue() > best && player.hasPermission(e.getKey())) best = e.getValue();
        }
        return best;
    }

    /** Cena pro konkrétního hráče (po slevě). */
    public double price(Player player, Island island, ChunkPos rel) {
        double base = price(island, rel);
        double off = discountPercent(player);
        if (off <= 0) return base;
        return Math.round(base * (100.0 - off)) / 100.0;
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

    private boolean check(Player player, Island island, ChunkPos rel, boolean needPermission) {
        Messages msg = plugin.messages();
        if (!island.isMember(player.getUniqueId())) {
            msg.send(player, "unlock.not-member");
            return false;
        }
        if (needPermission && !island.hasPermission(player.getUniqueId(), IslandPermission.UNLOCK)) {
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
        // týmové odemykání: itemy může odevzdat každý člen (UNLOCK permise pak hlídá jen platbu penězi)
        if (!check(player, island, rel, !plugin.settings().teamDeliverWithoutPermission)) return;
        Messages msg = plugin.messages();

        StringBuilder summary = new StringBuilder();
        int totalTaken = 0;
        for (Settings.Requirement req : requirements(island, rel)) {
            int need = remaining(island, rel, req);
            if (need <= 0) continue;
            int taken = takeItems(player, req.material(), need);
            if (taken <= 0) continue;
            island.addProgress(rel, req.material(), taken);
            island.addContribution(rel, player.getUniqueId(), taken);
            totalTaken += taken;
            if (summary.length() > 0) summary.append(", ");
            summary.append(taken).append("x ").append(pretty(req.material()));
        }

        boolean complete = isComplete(island, rel);
        if (totalTaken == 0 && !complete) {
            msg.send(player, "unlock.no-items");
            return;
        }
        if (totalTaken > 0) {
            msg.send(player, "unlock.delivered", ph("items", summary));
            if (!complete) notifyTeam(player, island, rel, summary.toString());
        }

        if (complete) {
            complete(player, island, rel);
        } else {
            plugin.storage().saveIsland(island);
            plugin.panels().refresh(island, rel);
        }
    }

    /** Hráč klikl na cedulku se svitkem v ruce - chunk se odemkne zdarma a svitek ztratí jeden náboj. */
    public void useToken(Player player, Island island, ChunkPos rel) {
        // unlock-token.require-permission: true = jen majitel a členové s oprávněním "unlock", false = každý člen
        boolean needPermission = plugin.getConfig().getBoolean("unlock-token.require-permission", false);
        if (!check(player, island, rel, needPermission)) return;
        int left = tokens.consume(player);
        if (left < 0) return;
        plugin.messages().send(player, left > 0 ? "token.used" : "token.used-last", ph("left", left));
        complete(player, island, rel);
    }

    /** Hráč kliknul se shiftem - zaplatí zbytek penězi. */
    public void pay(Player player, Island island, ChunkPos rel) {
        if (!check(player, island, rel, true)) return;
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
        double price = price(player, island, rel);
        double discount = discountPercent(player);
        if (price > 0) {
            if (!eco.has(player, price) || !eco.withdraw(player, price)) {
                msg.send(player, "unlock.not-enough-money", ph("price", formatPrice(price)));
                return;
            }
            if (discount > 0) {
                msg.send(player, "unlock.paid-discount", ph("price", formatPrice(price)),
                        ph("percent", trim(discount)));
            } else {
                msg.send(player, "unlock.paid", ph("price", formatPrice(price)));
            }
        }
        complete(player, island, rel);
    }

    private void complete(Player player, Island island, ChunkPos rel) {
        String contributors = contributorSummary(island, rel); // dřív, než unlockChunk smaže postup
        plugin.islands().unlockChunk(island, rel);
        Set<UUID> ids = new HashSet<>(island.members().keySet());
        ids.add(island.owner());
        for (UUID id : ids) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                plugin.messages().send(p, "unlock.success", ph("x", rel.x()), ph("z", rel.z()));
                if (contributors != null) plugin.messages().send(p, "unlock.contributors", ph("list", contributors));
            }
        }
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f);
    }

    /** "Alex (40), Bob (12)" - kdo přispěl k chunku; null když přispěl jen jeden (nebo nikdo). */
    private String contributorSummary(Island island, ChunkPos rel) {
        Map<UUID, Integer> map = island.contributorsOf(rel);
        if (map.size() < 2) return null;
        List<Map.Entry<UUID, Integer>> entries = new ArrayList<>(map.entrySet());
        entries.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<UUID, Integer> e : entries) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(island.nameOf(e.getKey())).append(" (").append(e.getValue()).append(')');
        }
        return sb.toString();
    }

    /** Dá vědět ostatním členům online, že někdo přidal itemy k odemčení chunku. */
    private void notifyTeam(Player from, Island island, ChunkPos rel, String items) {
        if (!plugin.settings().teamNotify) return;
        int total = 0;
        int done = 0;
        for (Settings.Requirement r : requirements(island, rel)) {
            total += r.amount();
            done += Math.min(r.amount(), island.progress(rel, r.material()));
        }
        int percent = total <= 0 ? 100 : (int) Math.floor(done * 100.0 / total);
        Set<UUID> ids = new HashSet<>(island.members().keySet());
        ids.add(island.owner());
        ids.remove(from.getUniqueId());
        for (UUID id : ids) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                plugin.messages().send(p, "unlock.team-delivered", ph("player", from.getName()),
                        ph("items", items), ph("x", rel.x()), ph("z", rel.z()), ph("percent", percent));
            }
        }
    }

    private static String trim(double v) {
        return v == Math.floor(v) ? String.valueOf((long) v) : String.valueOf(v);
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
