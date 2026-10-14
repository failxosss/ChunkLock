package dev.chunklock.model;

import org.bukkit.Material;

import java.util.Locale;
import java.util.Optional;

/** Oprávnění, která může majitel udělit členům svého chunku. */
public enum IslandPermission {
    BUILD(Material.BRICKS),
    BREAK(Material.IRON_PICKAXE),
    INTERACT(Material.OAK_DOOR),
    CONTAINERS(Material.CHEST),
    ANIMALS(Material.WHEAT),
    UNLOCK(Material.GOLD_INGOT),
    INVITE(Material.WRITABLE_BOOK);

    private final Material icon;

    IslandPermission(Material icon) {
        this.icon = icon;
    }

    public Material icon() {
        return icon;
    }

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Optional<IslandPermission> parse(String input) {
        if (input == null) return Optional.empty();
        for (IslandPermission p : values()) {
            if (p.name().equalsIgnoreCase(input)) return Optional.of(p);
        }
        return Optional.empty();
    }
}
