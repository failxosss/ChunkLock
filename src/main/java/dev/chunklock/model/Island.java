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

    private final UUID owner;
    private String ownerName;
    private final int gridX;
    private final int gridZ;
    private final Set<Long> unlocked = new HashSet<>();
    private final Map<Long, Map<Material, Integer>> progress = new HashMap<>();
    private final Map<UUID, Member> members = new LinkedHashMap<>();
    private boolean mobSpawning = true;

    public Island(UUID owner, String ownerName, int gridX, int gridZ) {
        this.owner = owner;
        this.ownerName = ownerName;
        this.gridX = gridX;
        this.gridZ = gridZ;
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

    public boolean mobSpawning() {
        return mobSpawning;
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

    public int unlockedCount() {
        return unlocked.size();
    }

    public Set<Long> unlockedKeys() {
        return unlocked;
    }

    public void reset() {
        unlocked.clear();
        progress.clear();
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
    }

    public Map<Long, Map<Material, Integer>> allProgress() {
        return progress;
    }

    // ---- členové ----

    public boolean isMember(UUID uuid) {
        return owner.equals(uuid) || members.containsKey(uuid);
    }

    public boolean hasPermission(UUID uuid, IslandPermission permission) {
        if (owner.equals(uuid)) return true;
        Member m = members.get(uuid);
        return m != null && m.permissions().contains(permission);
    }

    public Map<UUID, Member> members() {
        return members;
    }

    public void addMember(UUID uuid, String name, Collection<IslandPermission> perms) {
        members.put(uuid, new Member(name, perms));
    }

    public void removeMember(UUID uuid) {
        members.remove(uuid);
    }

    public UUID findMember(String name) {
        for (Map.Entry<UUID, Member> e : members.entrySet()) {
            if (e.getValue().name().equalsIgnoreCase(name)) return e.getKey();
        }
        return null;
    }

    /** Přepne oprávnění, vrátí novou hodnotu. */
    public boolean togglePermission(UUID uuid, IslandPermission permission) {
        Member m = members.get(uuid);
        if (m == null) return false;
        if (m.permissions().contains(permission)) {
            m.permissions().remove(permission);
            return false;
        }
        m.permissions().add(permission);
        return true;
    }
}
