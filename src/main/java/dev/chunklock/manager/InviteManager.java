package dev.chunklock.manager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Čekající pozvánky na chunk (jen v paměti, vyprší po čase). */
public final class InviteManager {

    public record Invite(UUID islandOwner, String ownerName, String inviterName, long expiresAt) {}

    private final Map<UUID, List<Invite>> invites = new HashMap<>();

    public boolean add(UUID target, Invite invite) {
        List<Invite> list = invites.computeIfAbsent(target, k -> new ArrayList<>());
        list.removeIf(i -> i.expiresAt() < System.currentTimeMillis());
        for (Invite i : list) {
            if (i.islandOwner().equals(invite.islandOwner())) return false;
        }
        list.add(invite);
        return true;
    }

    public List<Invite> valid(UUID target) {
        List<Invite> list = invites.get(target);
        if (list == null) return List.of();
        list.removeIf(i -> i.expiresAt() < System.currentTimeMillis());
        return new ArrayList<>(list);
    }

    public void remove(UUID target, Invite invite) {
        List<Invite> list = invites.get(target);
        if (list != null) list.remove(invite);
    }

    public void clear(UUID target) {
        invites.remove(target);
    }
}
