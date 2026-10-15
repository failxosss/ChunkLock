package dev.chunklock.hook;

import dev.chunklock.ChunkLockPlugin;
import dev.chunklock.model.Island;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;

/**
 * PlaceholderAPI expanze pro ChunkLock.
 *
 * <pre>
 * %chunklock_top_&lt;1-10&gt;_name%      jméno hráče s N-tým nejvíce odemčenými chunky
 * %chunklock_top_&lt;1-10&gt;_value%     počet odemčených chunků
 * %chunklock_oldest_&lt;1-10&gt;_name%   jméno hráče s N-tým nejstarším chunkem (1 = nejstarší)
 * %chunklock_oldest_&lt;1-10&gt;_value%  stáří, např. "12d 4h"
 * %chunklock_oldest_&lt;1-10&gt;_date%   datum vytvoření, např. "03.09.2026"
 * %chunklock_oldest_&lt;1-10&gt;_days%   stáří ve dnech (číslo)
 * %chunklock_unlocked%             počet odemčených chunků hráče
 * %chunklock_age%                  stáří chunku hráče
 * </pre>
 */
public final class ChunkLockExpansion extends PlaceholderExpansion {

    private static final int LIMIT = 10;
    private static final long MIN = 60_000L;
    private static final long HOUR = 60 * MIN;
    private static final long DAY = 24 * HOUR;

    private record Entry(String name, int chunks, long created) {}

    private final ChunkLockPlugin plugin;
    // snapshoty se plní na hlavním vlákně, DecentHolograms je čte asynchronně
    private volatile List<Entry> topChunks = List.of();
    private volatile List<Entry> oldest = List.of();

    public ChunkLockExpansion(ChunkLockPlugin plugin) {
        this.plugin = plugin;
        // první přepočet hned po startu, pak každých 10 s
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::refresh, 1L, 200L);
    }

    private void refresh() {
        List<Entry> list = new ArrayList<>();
        for (Island i : plugin.islands().all()) {
            list.add(new Entry(i.ownerName(), i.unlockedCount(), i.created()));
        }

        List<Entry> byChunks = new ArrayList<>(list);
        byChunks.sort(Comparator.comparingInt(Entry::chunks).reversed()
                .thenComparingLong(Entry::created));
        topChunks = limit(byChunks);

        List<Entry> byAge = new ArrayList<>(list);
        byAge.sort(Comparator.comparingLong(Entry::created));
        oldest = limit(byAge);
    }

    private static List<Entry> limit(List<Entry> list) {
        return List.copyOf(list.size() > LIMIT ? list.subList(0, LIMIT) : list);
    }

    @Override
    public String getIdentifier() {
        return "chunklock";
    }

    @Override
    public String getAuthor() {
        return "Failxos";
    }

    @Override
    public String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, String params) {
        String key = params.toLowerCase();

        if (key.equals("unlocked") || key.equals("age")) {
            if (player == null) return "0";
            Island i = plugin.islands().byOwner(player.getUniqueId());
            if (i == null) return key.equals("age") ? "-" : "0";
            return key.equals("unlocked") ? String.valueOf(i.unlockedCount()) : formatAge(i.created());
        }

        String[] p = key.split("_");
        if (p.length != 3) return null;
        boolean isTop = p[0].equals("top");
        if (!isTop && !p[0].equals("oldest")) return null;

        int n;
        try {
            n = Integer.parseInt(p[1]);
        } catch (NumberFormatException ex) {
            return null;
        }

        List<Entry> list = isTop ? topChunks : oldest;
        boolean exists = n >= 1 && n <= list.size();
        Entry e = exists ? list.get(n - 1) : null;

        return switch (p[2]) {
            case "name" -> exists ? e.name() : "-";
            case "value" -> {
                if (isTop) yield exists ? String.valueOf(e.chunks()) : "0";
                yield exists ? formatAge(e.created()) : "-";
            }
            case "date" -> exists ? new SimpleDateFormat("dd.MM.yyyy").format(new Date(e.created())) : "-";
            case "days" -> exists ? String.valueOf(Math.max(0, (System.currentTimeMillis() - e.created()) / DAY)) : "0";
            default -> null;
        };
    }

    private static String formatAge(long created) {
        long diff = Math.max(0, System.currentTimeMillis() - created);
        long d = diff / DAY;
        long h = (diff % DAY) / HOUR;
        long m = (diff % HOUR) / MIN;
        if (d > 0) return d + "d " + h + "h";
        if (h > 0) return h + "h " + m + "m";
        return m + "m";
    }
}
