package dev.chunklock.config;

import dev.chunklock.model.IslandPermission;
import org.bukkit.Material;
import org.bukkit.TreeType;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;
import java.util.logging.Logger;

/** Načtená a ověřená konfigurace (config.yml). Při reloadu se vytvoří nová instance. */
public final class Settings {

    public record Requirement(Material material, int amount) {}

    public record Tier(int from, double money, List<Requirement> items) {}

    public record TreeSettings(boolean enabled, boolean highestFirst, NavigableMap<Integer, Double> chances,
                               List<TreeType> types, int minDistance) {}

    public record Ore(Material material, int amount, double chance, int vein, int minDepth, int maxDepth) {}

    public record OreSettings(boolean enabled, List<Ore> list) {}

    public enum ListMode { NONE, BLACKLIST, WHITELIST }

    public record CommandRule(ListMode mode, Set<String> commands) {}

    // svět
    public final String worldName;
    public final boolean createWorld;
    public final boolean voidRescue;
    public final int voidRescueY;

    // ostrov
    public final int maxRadius;
    public final int gap;
    public final int distanceBlocks;
    public final int surfaceY;
    public final Material topBlock;
    public final Material fillerBlock;
    public final int fillerDepth;
    public final boolean bedrockBottom;
    public final Material stoneBlock;
    public final int stoneDepth;
    public final OreSettings startOres;
    public final OreSettings unlockedOres;
    public final TreeSettings startTrees;
    public final TreeSettings unlockedTrees;

    // odemykání
    public final boolean strictItems;
    public final boolean moneyEnabled;
    public final boolean reduceByProgress;
    public final List<Tier> tiers;

    // panel
    public final double panelHeight;
    public final double panelOffset;
    public final float titleScale;
    public final float itemScale;
    public final double itemSpacing;
    public final float viewRange;

    // členové
    public final int maxMembers;
    public final int inviteExpireSeconds;
    public final boolean teleportOnAccept;
    public final Set<IslandPermission> defaultPermissions;

    // ochrana
    public final boolean pvp;
    public final boolean explosions;

    // příkazy / světy
    public final Set<String> chunkEnabledWorlds;
    public final Set<String> chunkDisabledWorlds;
    private final Map<String, CommandRule> commandRules = new HashMap<>();

    public Settings(FileConfiguration cfg, Logger log) {
        worldName = cfg.getString("chunk-world.name", "chunks");
        createWorld = cfg.getBoolean("chunk-world.create-if-missing", true);
        voidRescue = cfg.getBoolean("chunk-world.void-rescue", true);
        voidRescueY = cfg.getInt("chunk-world.void-rescue-below-y", 20);

        maxRadius = Math.max(1, cfg.getInt("island.max-radius", 8));
        gap = Math.max(2, cfg.getInt("island.gap", 4));
        distanceBlocks = Math.max(0, cfg.getInt("island.distance-blocks", 2000));
        surfaceY = cfg.getInt("island.terrain.surface-y", 64);
        topBlock = material(cfg.getString("island.terrain.top-block"), Material.GRASS_BLOCK, log);
        fillerBlock = material(cfg.getString("island.terrain.filler-block"), Material.DIRT, log);
        fillerDepth = Math.max(0, cfg.getInt("island.terrain.filler-depth", 3));
        bedrockBottom = cfg.getBoolean("island.terrain.bedrock-bottom", true);
        stoneBlock = material(cfg.getString("island.terrain.stone-block"), Material.STONE, log);
        stoneDepth = Math.max(0, cfg.getInt("island.terrain.stone-depth", 24));
        startOres = readOres(cfg.getConfigurationSection("island.start-chunk.ores"), log);
        unlockedOres = readOres(cfg.getConfigurationSection("island.unlocked-chunks.ores"), log);
        startTrees = readTrees(cfg.getConfigurationSection("island.start-chunk.trees"), log);
        unlockedTrees = readTrees(cfg.getConfigurationSection("island.unlocked-chunks.trees"), log);

        strictItems = cfg.getBoolean("unlock.strict-items", true);
        moneyEnabled = cfg.getBoolean("unlock.money.enabled", true);
        reduceByProgress = cfg.getBoolean("unlock.money.reduce-by-progress", true);
        tiers = readTiers(cfg, log);

        panelHeight = cfg.getDouble("panel.height", 1.0);
        panelOffset = cfg.getDouble("panel.offset", 1.5);
        titleScale = (float) cfg.getDouble("panel.title-scale", 2.0);
        itemScale = (float) cfg.getDouble("panel.item-scale", 0.9);
        itemSpacing = cfg.getDouble("panel.item-spacing", 1.3);
        viewRange = (float) cfg.getDouble("panel.view-range", 0.6);

        maxMembers = Math.max(0, cfg.getInt("members.max-members", 5));
        inviteExpireSeconds = Math.max(5, cfg.getInt("members.invite-expire-seconds", 60));
        teleportOnAccept = cfg.getBoolean("members.teleport-on-accept", true);
        EnumSet<IslandPermission> perms = EnumSet.noneOf(IslandPermission.class);
        for (String s : cfg.getStringList("members.default-permissions")) {
            IslandPermission.parse(s).ifPresentOrElse(perms::add,
                    () -> log.warning("Neznámé oprávnění v members.default-permissions: " + s));
        }
        defaultPermissions = perms;

        pvp = cfg.getBoolean("protection.pvp", false);
        explosions = cfg.getBoolean("protection.explosions", false);

        chunkEnabledWorlds = lowerSet(cfg.getStringList("chunk-command.enabled-worlds"));
        chunkDisabledWorlds = lowerSet(cfg.getStringList("chunk-command.disabled-worlds"));

        ConfigurationSection rules = cfg.getConfigurationSection("world-commands");
        if (rules != null) {
            for (String world : rules.getKeys(false)) {
                ConfigurationSection sec = rules.getConfigurationSection(world);
                if (sec == null) continue;
                ListMode mode;
                try {
                    mode = ListMode.valueOf(sec.getString("mode", "NONE").toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException ex) {
                    log.warning("Neplatný mode u world-commands." + world);
                    mode = ListMode.NONE;
                }
                Set<String> list = new HashSet<>();
                for (String c : sec.getStringList("list")) {
                    list.add(c.toLowerCase(Locale.ROOT).replaceFirst("^/", ""));
                }
                commandRules.put(world.toLowerCase(Locale.ROOT), new CommandRule(mode, list));
            }
        }
    }

    /** Pravidlo příkazů pro svět, případně pravidlo "*". */
    public CommandRule ruleFor(String world) {
        CommandRule rule = commandRules.get(world.toLowerCase(Locale.ROOT));
        return rule != null ? rule : commandRules.get("*");
    }

    public boolean chunkCommandAllowed(String world) {
        String w = world.toLowerCase(Locale.ROOT);
        if (chunkDisabledWorlds.contains(w)) return false;
        return chunkEnabledWorlds.contains("*") || chunkEnabledWorlds.contains(w);
    }

    /** Odemykací tier podle počtu už odemčených chunků. */
    public Tier tierFor(int unlockedCount) {
        Tier chosen = tiers.get(0);
        for (Tier t : tiers) {
            if (t.from() <= unlockedCount) chosen = t;
        }
        return chosen;
    }

    // ------------------------------------------------------------------

    private static Set<String> lowerSet(List<String> in) {
        Set<String> out = new HashSet<>();
        for (String s : in) out.add(s.toLowerCase(Locale.ROOT));
        return out;
    }

    private static Material material(String name, Material def, Logger log) {
        if (name == null) return def;
        Material m = Material.matchMaterial(name);
        if (m == null || !m.isBlock()) {
            log.warning("Neplatný blok '" + name + "', používám " + def);
            return def;
        }
        return m;
    }

    private static int toInt(Object o, int def) {
        if (o instanceof Number n) return n.intValue();
        if (o != null) {
            try {
                return Integer.parseInt(o.toString().trim());
            } catch (NumberFormatException ignored) {
                // fallthrough
            }
        }
        return def;
    }

    private static double toDouble(Object o, double def) {
        if (o instanceof Number n) return n.doubleValue();
        if (o != null) {
            try {
                return Double.parseDouble(o.toString().trim());
            } catch (NumberFormatException ignored) {
                // fallthrough
            }
        }
        return def;
    }

    private static OreSettings readOres(ConfigurationSection sec, Logger log) {
        List<Ore> list = new ArrayList<>();
        if (sec == null || !sec.getBoolean("enabled", true)) return new OreSettings(false, list);
        for (Map<?, ?> map : sec.getMapList("list")) {
            Material m = Material.matchMaterial(String.valueOf(map.get("material")));
            if (m == null || !m.isBlock()) {
                log.warning("Neplatná ruda v ores.list: " + map.get("material"));
                continue;
            }
            int amount = Math.max(0, toInt(map.get("amount"), 0));
            double chance = Math.max(0, Math.min(100, toDouble(map.get("chance"), 100)));
            int vein = Math.max(1, toInt(map.get("vein-size"), 4));
            int minDepth = Math.max(1, toInt(map.get("min-depth"), 1));
            int maxDepth = Math.max(minDepth, toInt(map.get("max-depth"), 9999));
            list.add(new Ore(m, amount, chance, vein, minDepth, maxDepth));
        }
        return new OreSettings(true, List.copyOf(list));
    }

    private static TreeSettings readTrees(ConfigurationSection sec, Logger log) {
        NavigableMap<Integer, Double> chances = new TreeMap<>();
        List<TreeType> types = new ArrayList<>();
        if (sec == null) {
            return new TreeSettings(false, true, chances, List.of(TreeType.TREE), 4);
        }
        boolean enabled = sec.getBoolean("enabled", true);
        boolean highestFirst = !"CUMULATIVE".equalsIgnoreCase(sec.getString("mode", "HIGHEST_FIRST"));
        ConfigurationSection cs = sec.getConfigurationSection("chances");
        if (cs != null) {
            for (String key : cs.getKeys(false)) {
                try {
                    chances.put(Integer.parseInt(key), cs.getDouble(key));
                } catch (NumberFormatException ex) {
                    log.warning("Neplatný počet stromů v chances: " + key);
                }
            }
        }
        for (String s : sec.getStringList("types")) {
            try {
                types.add(TreeType.valueOf(s.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ex) {
                log.warning("Neznámý typ stromu: " + s);
            }
        }
        if (types.isEmpty()) types.add(TreeType.TREE);
        return new TreeSettings(enabled, highestFirst, chances, types, Math.max(1, sec.getInt("min-distance", 4)));
    }

    private static List<Tier> readTiers(FileConfiguration cfg, Logger log) {
        List<Tier> out = new ArrayList<>();
        for (Map<?, ?> map : cfg.getMapList("unlock.tiers")) {
            int from = toInt(map.get("from"), 0);
            double money = toDouble(map.get("money"), 0);
            List<Requirement> reqs = new ArrayList<>();
            if (map.get("items") instanceof List<?> list) {
                for (Object o : list) {
                    if (!(o instanceof Map<?, ?> im)) continue;
                    Material m = Material.matchMaterial(String.valueOf(im.get("material")));
                    if (m == null || m.isAir() || !m.isItem()) {
                        log.warning("Neplatný item v unlock.tiers: " + im.get("material"));
                        continue;
                    }
                    reqs.add(new Requirement(m, Math.max(1, toInt(im.get("amount"), 1))));
                }
            }
            out.add(new Tier(from, Math.max(0, money), List.copyOf(reqs)));
        }
        if (out.isEmpty()) {
            log.warning("unlock.tiers je prázdné, používám výchozí tier.");
            out.add(new Tier(0, 5000, List.of(new Requirement(Material.GOLD_INGOT, 250),
                    new Requirement(Material.OAK_LOG, 22))));
        }
        out.sort(Comparator.comparingInt(Tier::from));
        return List.copyOf(out);
    }
}
