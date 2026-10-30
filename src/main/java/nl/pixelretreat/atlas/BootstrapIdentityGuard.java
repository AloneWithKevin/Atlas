package nl.pixelretreat.atlas;

import nl.pixelretreat.postbox.api.PostboxBootstrapIdentity;

/** Fails Atlas startup before database or world work when the Postbox bootstrap node is unavailable or differs. */
final class BootstrapIdentityGuard {
    private BootstrapIdentityGuard() {}

    static void requireMatch(final PostboxBootstrapIdentity identity, final String atlasServer) {
        if (identity == null) {
            throw new IllegalStateException("Postbox bootstrap identity is unavailable");
        }
        final String postboxServer = identity.localServer().value();
        if (!postboxServer.equals(atlasServer)) {
            throw new IllegalStateException("Atlas server is " + atlasServer
                + " but Postbox bootstrap identity is " + postboxServer);
        }
    }
}
