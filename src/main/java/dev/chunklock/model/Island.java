package dev.chunklock.model;

import org.bukkit.Material;

import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Ostrov (parcela) jednoho hráče. */
public final class Island {

    public static final class Member {
        private String name;
        private final EnumSet<IslandPermission> permissions;

        public Member(String name, Collection<IslandPermission> permissions) {
            this.name = name;
            this.permissions = EnumSet.noneOf(IslandPermission.class);
            this.permissions.addAll(permissions);
        }

        public String name() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public EnumSet<IslandPermission> permissions() {
            return permissions;
        }
    }

    private final Dimension dimension;
    /** Ostrov v Netheru / Endu patří k ostrovu v overworldu (sdílí členy a oprávnění). U overworldu null. */
    private Island parent;
    private final UUID owner;
    private String ownerName;
    private final int gridX;
    private final int gridZ;
    private final Set<Long> unlocked = new HashSet<>();
    private final Map<Long, Map<Material, Integer>> progress = new HashMap<>();
    /** Suroviny (počty bloků) v odemčených chuncích - zjištěné při jejich vygenerování. */
    private final Map<Long, Map<Material, Integer>> resources = new HashMap<>();
    /** Spočítané požadavky zamčených chunků (drží se, aby se čísla na cedulce nepřepisovala). */
    private final Map<Long, Map<Material, Integer>> required = new HashMap<>();
    private final Map<UUID, Member> members = new LinkedHashMap<>();
    /** Kolik itemů celkem odevzdal každý hráč (týmové odemykání). */
    private final Map<UUID, Integer> contributions = new HashMap<>();
    /** Kdo kolik odevzdal u jednotlivých zamčených chunků (maže se při odemčení). */
    private final Map<Long, Map<UUID, Integer>> contributors = new HashMap<>();
    private boolean mobSpawning = true;
    /** End: drak už byl zabit (aktivní výstupní portál). */
    private boolean dragonKilled;
    /** Výška podlahy u startovního chunku (Nether: referenční výška pro cedulky a spawn). */
    private int refY = Integer.MIN_VALUE;
    /** Počátek terénu ve zdrojovém světě (chunk souřadnice); null = ostrov je plochý (starý způsob). */
    private Integer sourceX;
    private Integer sourceZ;
    /** Čas vytvoření ostrova (epoch ms) - pro žebříček nejstarších chunků. */
    private long created = System.currentTimeMillis();

    public Island(UUID owner, String ownerName, int gridX, int gridZ) {
        this(Dimension.OVERWORLD, null, owner, ownerName, gridX, gridZ);
    }

    public Island(Dimension dimension, Island parent, UUID owner, String ownerName, int gridX, int gridZ) {
        this.dimension = dimension;
        this.parent = parent;
        this.owner = owner;
        this.ownerName = ownerName;
        this.gridX = gridX;
        this.gridZ = gridZ;
    }

    public Dimension dimension() {
        return dimension;
    }

    public Island parent() {
        return parent;
    }

    public void setParent(Island parent) {
        this.parent = parent;
    }

    public boolean dragonKilled() {
        return dragonKilled;
    }

    public void setDragonKilled(boolean dragonKilled) {
        this.dragonKilled = dragonKilled;
    }

    public boolean hasRefY() {
        return refY != Integer.MIN_VALUE;
    }

    public int refY() {
        return refY;
    }

    public void setRefY(int refY) {
        this.refY = refY;
    }

    public UUID owner() {
        return owner;
    }

    public String ownerName() {
        return ownerName;
    }

    public void setOwnerName(String ownerName) {
        this.ownerName = ownerName;
    }

    public int gridX() {
        return gridX;
    }

    public int gridZ() {
        return gridZ;
    }

    public boolean hasSource() {
        return sourceX != null && sourceZ != null;
    }

    public int srcX() {
        return sourceX == null ? 0 : sourceX;
    }

    public int srcZ() {
        return sourceZ == null ? 0 : sourceZ;
    }

    public void setSource(int x, int z) {
        this.sourceX = x;
        this.sourceZ = z;
    }

    public void clearSource() {
        this.sourceX = null;
        this.sourceZ = null;
    }

    public long created() {
        return created;
    }

    public void setCreated(long created) {
        this.created = created;
    }

    public boolean mobSpawning() {
        return parent != null ? parent.mobSpawning() : mobSpawning;
    }

    public void setMobSpawning(boolean mobSpawning) {
        this.mobSpawning = mobSpawning;
    }

    // ---- chunky ----

    public boolean isUnlocked(ChunkPos rel) {
        return unlocked.contains(rel.key());
    }

    public void unlock(ChunkPos rel) {
        unlocked.add(rel.key());
    }

    public void lock(ChunkPos rel) {
        unlocked.remove(rel.key());
    }

    public int unlockedCount() {
        return unlocked.size();
    }

    public Set<Long> unlockedKeys() {
        return unlocked;
    }

    public void reset() {
        unlocked.clear();
        progress.clear();
        resources.clear();
        required.clear();
        dragonKilled = false;
        refY = Integer.MIN_VALUE;
        contributions.clear();
        contributors.clear();
        created = System.currentTimeMillis();
        unlocked.add(new ChunkPos(0, 0).key());
    }

    // ---- postup odemykání ----

    public int progress(ChunkPos rel, Material material) {
        Map<Material, Integer> map = progress.get(rel.key());
        return map == null ? 0 : map.getOrDefault(material, 0);
    }

    public void setProgress(ChunkPos rel, Material material, int amount) {
        progress.computeIfAbsent(rel.key(), k -> new HashMap<>()).put(material, amount);
    }

    public void addProgress(ChunkPos rel, Material material, int amount) {
        setProgress(rel, material, progress(rel, material) + amount);
    }

    public void clearProgress(ChunkPos rel) {
        progress.remove(rel.key());
        contributors.remove(rel.key());
    }

    // ---- příspěvky členů ----

    public void addContribution(ChunkPos rel, UUID who, int amount) {
        if (amount <= 0) return;
        contributions.merge(who, amount, Integer::sum);
        contributors.computeIfAbsent(rel.key(), k -> new HashMap<>()).merge(who, amount, Integer::sum);
    }

    public Map<UUID, Integer> contributions() {
        return contributions;
    }

    public Map<Long, Map<UUID, Integer>> allContributors() {
        return contributors;
    }

    /** Kdo přispěl k jednomu chunku (nikdy null). */
    public Map<UUID, Integer> contributorsOf(ChunkPos rel) {
        Map<UUID, Integer> map = contributors.get(rel.key());
        return map == null ? Map.of() : map;
    }

    /** Jméno majitele / člena (nebo "?"). */
    public String nameOf(UUID uuid) {
        if (owner.equals(uuid)) return ownerName;
        Member m = members().get(uuid);
        return m == null ? "?" : m.name();
    }

    public Map<Long, Map<Material, Integer>> allProgress() {
        return progress;
    }

    /** Odevzdané itemy jednoho chunku (nikdy null). */
    public Map<Material, Integer> progressOf(ChunkPos rel) {
        Map<Material, Integer> map = progress.get(rel.key());
        return map == null ? Map.of() : map;
    }

    // ---- suroviny v chuncích a požadavky ----

    /** Uložený sken chunku (počty bloků), nebo null když ještě nebyl proveden. */
    public Map<Material, Integer> resources(ChunkPos rel) {
        return resources.get(rel.key());
    }

    public void setResources(ChunkPos rel, Map<Material, Integer> counts) {
        resources.put(rel.key(), counts);
    }

    public void clearResources(ChunkPos rel) {
        resources.remove(rel.key());
    }

    public Map<Long, Map<Material, Integer>> allResources() {
        return resources;
    }

    /** Uložené požadavky zamčeného chunku (zachovává pořadí), nebo null když ještě nebyly spočítány. */
    public Map<Material, Integer> required(ChunkPos rel) {
        return required.get(rel.key());
    }

    public void setRequired(ChunkPos rel, Map<Material, Integer> req) {
        required.put(rel.key(), req);
    }

    public void clearRequired(ChunkPos rel) {
        required.remove(rel.key());
    }

    public void clearAllRequired() {
        required.clear();
    }

    public Map<Long, Map<Material, Integer>> allRequired() {
        return required;
    }

    // ---- členové ----

    public boolean isMember(UUID uuid) {
        return owner.equals(uuid) || members().containsKey(uuid);
    }

    public boolean hasPermission(UUID uuid, IslandPermission permission) {
        if (owner.equals(uuid)) return true;
        Member m = members().get(uuid);
        return m != null && m.permissions().contains(permission);
    }

    /** Členové (u ostrova v Netheru / Endu se berou z ostrova v overworldu). */
    public Map<UUID, Member> members() {
        return parent != null ? parent.members() : members;
    }

    public void addMember(UUID uuid, String name, Collection<IslandPermission> perms) {
        members().put(uuid, new Member(name, perms));
    }

    public void removeMember(UUID uuid) {
        members().remove(uuid);
    }

    public UUID findMember(String name) {
        for (Map.Entry<UUID, Member> e : members().entrySet()) {
            if (e.getValue().name().equalsIgnoreCase(name)) return e.getKey();
        }
        return null;
    }

    /** Přepne oprávnění, vrátí novou hodnotu. */
    public boolean togglePermission(UUID uuid, IslandPermission permission) {
        Member m = members().get(uuid);
        if (m == null) return false;
        if (m.permissions().contains(permission)) {
            m.permissions().remove(permission);
            return false;
        }
        m.permissions().add(permission);
        return true;
    }
}
