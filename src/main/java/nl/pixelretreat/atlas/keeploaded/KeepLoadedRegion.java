package nl.pixelretreat.atlas.keeploaded;

/** A rectangle of chunks that Atlas keeps loaded with plugin chunk tickets. */
public record KeepLoadedRegion(String name, String world, int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ) {

    /** Builds the region that covers two block corners; the Y axis is irrelevant for chunks. */
    public static KeepLoadedRegion fromBlocks(String name, String world, int x1, int z1, int x2, int z2) {
        return new KeepLoadedRegion(name, world,
                Math.floorDiv(Math.min(x1, x2), 16), Math.floorDiv(Math.min(z1, z2), 16),
                Math.floorDiv(Math.max(x1, x2), 16), Math.floorDiv(Math.max(z1, z2), 16));
    }

    /** Number of chunks covered, without integer overflow. */
    public long chunkCount() {
        return ((long) maxChunkX - minChunkX + 1) * ((long) maxChunkZ - minChunkZ + 1);
    }
}
