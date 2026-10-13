package nl.pixelretreat.atlas;

import nl.pixelretreat.postbox.api.PostboxBootstrapIdentity;

/** Fails Atlas startup before database or world work when the Postbox bootstrap node is unavailable or differs. */
final class BootstrapIdentityGuard {
    private BootstrapIdentityGuard() {}

    static void requireMatch(final PostboxBootstrapIdentity identity, final String atlasRegion) {
        if (identity == null) {
            throw new IllegalStateException("Postbox bootstrap identity is unavailable");
        }
        final String postboxRegion = identity.localNode().name();
        if (!postboxRegion.equals(atlasRegion)) {
            throw new IllegalStateException("Atlas region is " + atlasRegion
                + " but Postbox bootstrap identity is " + postboxRegion);
        }
    }
}
