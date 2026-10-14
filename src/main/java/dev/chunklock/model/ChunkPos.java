package dev.chunklock.model;

/** Pozice chunku (relativní k startovnímu chunku ostrova, nebo absolutní). */
public record ChunkPos(int x, int z) {

    public long key() {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    public static ChunkPos fromKey(long key) {
        return new ChunkPos((int) (key >> 32), (int) key);
    }

    public ChunkPos offset(int dx, int dz) {
        return new ChunkPos(x + dx, z + dz);
    }

    /** Čebyševova vzdálenost od (0,0). */
    public int distance() {
        return Math.max(Math.abs(x), Math.abs(z));
    }

    public String serialize() {
        return x + "," + z;
    }

    public static ChunkPos parse(String s) {
        String[] p = s.split(",");
        return new ChunkPos(Integer.parseInt(p[0].trim()), Integer.parseInt(p[1].trim()));
    }
}
