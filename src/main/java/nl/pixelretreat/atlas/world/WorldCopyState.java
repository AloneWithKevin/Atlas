package nl.pixelretreat.atlas.world;

/** Durable preparation/publication state; no phase is inferred from a folder name alone. */
public record WorldCopyState(Phase phase, boolean activateAfterCopy, long preparedEntries, long originalEntries) {
    /** Every intent is committed before its corresponding filesystem change. */
    public enum Phase { QUEUED, COPYING, PREPARED, MOVING_OLD, PUBLISHING, PUBLISHED, APPLIED, UNCERTAIN, CANCELLED }

    /** One exact entry of an immutable prepared or original tree. */
    public record Entry(String path, boolean directory, long bytes, String sha256) { }
}
