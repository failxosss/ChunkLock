package dev.chunklock.model;

import org.bukkit.World;

/** Dimenze pluginu: každá má vlastní void svět, vlastní ostrovy a vlastní zdrojový svět pro terén. */
public enum Dimension {
    OVERWORLD("overworld", World.Environment.NORMAL),
    NETHER("nether", World.Environment.NETHER),
    END("end", World.Environment.THE_END);

    private final String id;
    private final World.Environment environment;

    Dimension(String id, World.Environment environment) {
        this.id = id;
        this.environment = environment;
    }

    public String id() {
        return id;
    }

    public World.Environment environment() {
        return environment;
    }

    public static Dimension parse(String s) {
        if (s != null) {
            for (Dimension d : values()) {
                if (d.id.equalsIgnoreCase(s.trim())) return d;
            }
        }
        return OVERWORLD;
    }
}
