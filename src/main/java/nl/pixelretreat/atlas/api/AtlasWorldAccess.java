package nl.pixelretreat.atlas.api;

import java.util.concurrent.CompletionStage;

/** Local world access, owned by Atlas. Closing keeps a world loaded for staff. */
public interface AtlasWorldAccess {
    enum Result { DONE, UNKNOWN_WORLD, BUSY, SPAWN_UNAVAILABLE, EVICTION_INCOMPLETE, FAILED }
    CompletionStage<Result> setClosed(String worldKey, boolean closed);
    boolean closed(String worldKey);
}
