package dev.chunklock.config;

import dev.chunklock.model.Dimension;
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

    /**
     * Surovina, kterou jde v chunku získat. drop = item, který hráč odevzdá; blocks = bloky, ze kterých se dostane
     * (perBlock = kolik itemů z jednoho bloku počítáme); weight = váha při výběru; from = od kolika odemčených
     * chunků se smí vyžadovat; minPool = kolik jí musí v chunku být, aby se vůbec vyžadovala; max = strop množství;
     * core = první požadavek chunku se vybírá vždy mezi "jádrovými" surovinami (rudy, dřevo).
     */
    public record ResourceRule(String id, Material drop, Set<Material> blocks, double perBlock, int weight,
                               int from, int minPool, int max, boolean core) {}

    public record TreeSettings(boolean enabled, boolean highestFirst, NavigableMap<Integer, Double> chances,
                               List<TreeType> types, int minDistance) {}

    public record Ore(Material material, int amount, double chance, int vein, int minDepth, int maxDepth) {}

    public record OreSettings(boolean enabled, List<Ore> list) {}

    public enum ListMode { NONE, BLACKLIST, WHITELIST }

    /** Nastavení jedné dimenze: svět pro ostrovy, zdrojový svět terénu, rozsah odemykání, hledání startu. */
    public record DimensionSettings(boolean enabled, String world, String sourceWorld, Long seed, int maxRadius,
                                    int startRange) {}

    public record CommandRule(ListMode mode, Set<String> commands) {}

    // svět
    public final String worldName;
    public final boolean createWorld;
    public final boolean voidRescue;
    public final int voidRescueY;

    // generování chunků z normálního světa
    public final boolean worldGeneration;
    public final String sourceWorldName;
    public final Long sourceSeed;
    public final boolean sourceStructures;
    public final int layersPerTick;
    public final int startAttempts;
    public final int startRange;

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
    /** true = požadavky se počítají podle surovin v sousedním chunku, false = pevné tiery. */
    public final boolean chunkMode;
    public final List<ResourceRule> resourceRules;
    private final Map<Dimension, List<ResourceRule>> rulesByDim = new java.util.EnumMap<>(Dimension.class);
    private final Map<Dimension, Set<Material>> blocksByDim = new java.util.EnumMap<>(Dimension.class);
    private final Map<Dimension, DimensionSettings> dimensions = new java.util.EnumMap<>(Dimension.class);
    /** Další světy (kromě chunk světa), ve kterých portály vedou hráče na jeho nether / end ostrov. */
    public final Set<String> extraPortalWorlds;
    /** End: spawnovat draka na ostrově a jak daleko od středu ostrova se může vzdálit. */
    public final boolean dragonEnabled;
    public final int dragonLeash;
    /** Všechny bloky, které se při skenu chunku počítají. */
    public final Set<Material> resourceBlocks;
    public final double shareStart;
    public final double sharePerChunk;
    public final double shareMax;
    public final double maxSharePerItem;
    public final double shareVariance;
    public final int chunkMinItems;
    public final int chunkMaxItems;
    public final int chunkMinAmount;
    public final boolean strictItems;
    public final boolean moneyEnabled;
    public final boolean reduceByProgress;
    public final List<Tier> tiers;
    /** Obtížnost podle vzdálenosti od startovního chunku. */
    public final boolean distanceEnabled;
    public final boolean distanceManhattan;
    public final double distancePerChunk;
    public final double distanceMax;
    public final boolean distanceMoney;
    /** Permise -> sleva v % při platbě penězi (platí nejvyšší, kterou hráč má). */
    public final Map<String, Double> moneyDiscounts;
    /** Týmové odemykání. */
    public final boolean teamDeliverWithoutPermission;
    public final boolean teamNotify;
    /** Zálohy ostrovů před resetem / smazáním. */
    public final boolean backupEnabled;
    public final int backupKeep;

    // panel
    public final double panelHeight;
    public final double panelOffset;
    public final float titleScale;
    public final float itemScale;
    public final double itemSpacing;
    public final float viewRange;
    public final boolean panelFixed;
    /** Patra cedulek pod sebou (každých stackSpacing bloků až k bedrocku). */
    public final boolean stackEnabled;
    public final double stackSpacing;
    public final int stackMaxLevels;
    public final int stackPerTick;

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
        worldName = cfg.getString("chunk-world.name", "chunkbasebase");
        createWorld = cfg.getBoolean("chunk-world.create-if-missing", true);
        voidRescue = cfg.getBoolean("chunk-world.void-rescue", true);
        voidRescueY = cfg.getInt("chunk-world.void-rescue-below-y", -72);

        worldGeneration = cfg.getString("generation.mode", "WORLD").equalsIgnoreCase("WORLD");
        sourceWorldName = cfg.getString("generation.world.name", "chunklock_source");
        String seedRaw = cfg.getString("generation.world.seed", "").trim();
        Long seed = null;
        if (!seedRaw.isEmpty()) {
            try {
                seed = Long.parseLong(seedRaw);
            } catch (NumberFormatException ex) {
                seed = (long) seedRaw.hashCode();
            }
        }
        sourceSeed = seed;
        sourceStructures = cfg.getBoolean("generation.world.structures", true);
        layersPerTick = Math.max(1, cfg.getInt("generation.world.layers-per-tick", 32));
        startAttempts = Math.max(0, cfg.getInt("generation.world.start-attempts", 12));
        startRange = Math.max(100, cfg.getInt("generation.world.start-range-chunks", 20000));

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

        List<ResourceRule> resourceList = readResources(cfg, "unlock.chunk.resources", log);
        resourceRules = List.copyOf(resourceList);
        Set<Material> blocks = EnumSet.noneOf(Material.class);
        for (ResourceRule r : resourceList) blocks.addAll(r.blocks());
        resourceBlocks = Set.copyOf(blocks);
        rulesByDim.put(Dimension.OVERWORLD, resourceRules);
        blocksByDim.put(Dimension.OVERWORLD, resourceBlocks);
        for (Dimension d : new Dimension[]{Dimension.NETHER, Dimension.END}) {
            List<ResourceRule> list = List.copyOf(readResources(cfg, "unlock.chunk.resources-" + d.id(), log));
            Set<Material> set = EnumSet.noneOf(Material.class);
            for (ResourceRule r : list) set.addAll(r.blocks());
            rulesByDim.put(d, list);
            blocksByDim.put(d, Set.copyOf(set));
        }
        boolean wantChunk = !"TIERS".equalsIgnoreCase(cfg.getString("unlock.mode", "CHUNK"));
        if (wantChunk && resourceList.isEmpty()) {
            log.warning("unlock.chunk.resources je prázdné - používám pevné tiery.");
            wantChunk = false;
        }
        chunkMode = wantChunk;
        shareStart = clamp(cfg.getDouble("unlock.chunk.share-start", 20), 1, 100);
        shareMax = clamp(cfg.getDouble("unlock.chunk.share-max", 45), shareStart, 100);
        sharePerChunk = Math.max(0, cfg.getDouble("unlock.chunk.share-per-chunk", 1.5));
        maxSharePerItem = clamp(cfg.getDouble("unlock.chunk.max-share-per-item", 60), 1, 100);
        shareVariance = clamp(cfg.getDouble("unlock.chunk.variance", 20), 0, 90);
        chunkMinItems = Math.max(1, cfg.getInt("unlock.chunk.min-items", 2));
        chunkMaxItems = Math.max(chunkMinItems, cfg.getInt("unlock.chunk.max-items", 3));
        chunkMinAmount = Math.max(1, cfg.getInt("unlock.chunk.min-amount", 3));
        strictItems = cfg.getBoolean("unlock.strict-items", true);
        moneyEnabled = cfg.getBoolean("unlock.money.enabled", true);
        reduceByProgress = cfg.getBoolean("unlock.money.reduce-by-progress", true);
        tiers = readTiers(cfg, log);

        dimensions.put(Dimension.OVERWORLD, new DimensionSettings(true, worldName, sourceWorldName, sourceSeed,
                maxRadius, startRange));
        dimensions.put(Dimension.NETHER, readDimension(cfg, Dimension.NETHER, "chunkbasebase_nether",
                "chunklock_source_nether", 8, 2000));
        dimensions.put(Dimension.END, readDimension(cfg, Dimension.END, "chunkbasebase_the_end",
                "chunklock_source_end", 4, 0));
        extraPortalWorlds = lowerSet(cfg.getStringList("dimensions.extra-portal-worlds"));
        dragonEnabled = cfg.getBoolean("dimensions.end.dragon.enabled", true);
        dragonLeash = Math.max(40, cfg.getInt("dimensions.end.dragon.leash-radius", 160));

        distanceEnabled = cfg.getBoolean("unlock.distance.enabled", true);
        distanceManhattan = "MANHATTAN".equalsIgnoreCase(cfg.getString("unlock.distance.metric", "CHEBYSHEV"));
        distancePerChunk = Math.max(0, cfg.getDouble("unlock.distance.per-chunk", 10));
        distanceMax = Math.max(0, cfg.getDouble("unlock.distance.max-bonus", 150));
        distanceMoney = cfg.getBoolean("unlock.distance.apply-to-money", true);

        Map<String, Double> discounts = new java.util.LinkedHashMap<>();
        ConfigurationSection disc = cfg.getConfigurationSection("unlock.money.discounts");
        if (disc != null) {
            // klíče obsahují tečky (chunklock.discount.vip), Bukkit je načte jako vnořené sekce -> hledáme do hloubky
            for (String perm : disc.getKeys(true)) {
                if (!(disc.get(perm) instanceof Number n)) continue;
                double pct = n.doubleValue();
                if (pct > 0) discounts.put(perm, Math.min(100, pct));
            }
        }
        moneyDiscounts = Map.copyOf(discounts);

        teamDeliverWithoutPermission = cfg.getBoolean("unlock.team.deliver-without-permission", true);
        teamNotify = cfg.getBoolean("unlock.team.notify", true);

        backupEnabled = cfg.getBoolean("backup.enabled", true);
        backupKeep = Math.max(0, cfg.getInt("backup.keep-per-island", 10));

        panelHeight = cfg.getDouble("panel.height", 1.0);
        panelOffset = cfg.getDouble("panel.offset", 1.5);
        titleScale = (float) cfg.getDouble("panel.title-scale", 2.0);
        itemScale = (float) cfg.getDouble("panel.item-scale", 0.9);
        itemSpacing = cfg.getDouble("panel.item-spacing", 1.3);
        viewRange = (float) cfg.getDouble("panel.view-range", 0.6);
        panelFixed = cfg.getBoolean("panel.fixed", true);
        stackEnabled = cfg.getBoolean("panel.stack.enabled", true);
        stackSpacing = Math.max(3, cfg.getDouble("panel.stack.spacing", 10));
        stackMaxLevels = Math.max(0, cfg.getInt("panel.stack.max-levels", 0));
        stackPerTick = Math.max(1, cfg.getInt("panel.stack.per-tick", 6));

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

    public DimensionSettings dimension(Dimension d) {
        return dimensions.get(d);
    }

    public List<ResourceRule> rulesFor(Dimension d) {
        return rulesByDim.get(d);
    }

    public Set<Material> blocksFor(Dimension d) {
        return blocksByDim.get(d);
    }

    /** Režim CHUNK platí v dimenzi jen když má nějaká pravidla surovin (jinak pevné tiery). */
    public boolean chunkModeFor(Dimension d) {
        return chunkMode && !rulesByDim.get(d).isEmpty();
    }

    private static DimensionSettings readDimension(FileConfiguration cfg, Dimension d, String defWorld,
                                                   String defSource, int defRadius, int defRange) {
        String p = "dimensions." + d.id() + ".";
        String seedRaw = cfg.getString(p + "seed", "").trim();
        Long seed = null;
        if (!seedRaw.isEmpty()) {
            try {
                seed = Long.parseLong(seedRaw);
            } catch (NumberFormatException ex) {
                seed = (long) seedRaw.hashCode();
            }
        }
        return new DimensionSettings(cfg.getBoolean(p + "enabled", true),
                cfg.getString(p + "world", defWorld),
                cfg.getString(p + "source-world", defSource),
                seed,
                Math.max(1, cfg.getInt(p + "max-radius", defRadius)),
                Math.max(100, cfg.getInt(p + "start-range-chunks", defRange)));
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

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

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

    /** Načte unlock.chunk.resources. Neznámé bloky (jiná verze serveru) se tiše přeskočí. */
    private static List<ResourceRule> readResources(FileConfiguration cfg, String path, Logger log) {
        List<ResourceRule> out = new ArrayList<>();
        for (Map<?, ?> map : cfg.getMapList(path)) {
            String id = String.valueOf(map.get("id"));
            boolean self = Boolean.parseBoolean(String.valueOf(map.get("self")));
            Material drop = null;
            if (!self) {
                drop = Material.matchMaterial(String.valueOf(map.get("drop")));
                if (drop == null || drop.isAir() || !drop.isItem()) {
                    log.warning("Neplatný drop v " + path + " (" + id + "): " + map.get("drop"));
                    continue;
                }
            }
            Set<Material> blocks = EnumSet.noneOf(Material.class);
            if (map.get("blocks") instanceof List<?> list) {
                for (Object o : list) {
                    Material b = Material.matchMaterial(String.valueOf(o));
                    if (b != null && b.isBlock() && !b.isAir()) blocks.add(b);
                }
            }
            if (blocks.isEmpty()) {
                log.warning(path + " (" + id + "): žádný platný blok, přeskakuji.");
                continue;
            }
            double perBlock = Math.max(0.01, toDouble(map.get("per-block"), 1));
            int weight = Math.max(1, toInt(map.get("weight"), 5));
            int from = Math.max(0, toInt(map.get("from"), 0));
            int minPool = Math.max(1, toInt(map.get("min-pool"), 4));
            int max = Math.max(1, toInt(map.get("max"), 256));
            boolean core = Boolean.parseBoolean(String.valueOf(map.get("core")));
            if (self) { // každý blok v seznamu je sám sobě dropem (dřevo, písek...) a je to samostatná surovina
                for (Material b : blocks) {
                    if (!b.isItem()) continue;
                    out.add(new ResourceRule(b.name().toLowerCase(Locale.ROOT), b, EnumSet.of(b), perBlock,
                            weight, from, minPool, max, core));
                }
            } else {
                out.add(new ResourceRule(id, drop, blocks, perBlock, weight, from, minPool, max, core));
            }
        }
        return out;
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
