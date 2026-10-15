// SPDX-License-Identifier: Apache-2.0
package org.freeholdos.duress.state;

/**
 * The persisted state of a device's duress (decoy) profile: either no decoy
 * is registered ({@link Off}) or one is ({@link Registered}).
 *
 * <p><strong>Callers must not branch on this type before calling
 * {@code DuressCredentialMatcher.matches(...)}.</strong> That method must be
 * invoked unconditionally on every unlock attempt regardless of which variant
 * is returned here — see {@code DuressCredentialMatcher}'s class javadoc for
 * why. Guarding the match call with an {@code instanceof Registered} check
 * (skipping it on {@link Off}) reintroduces the timing side-channel this
 * design exists to eliminate.
 */
public sealed interface DuressProfileState {

    record Off() implements DuressProfileState {
    }

    /**
     * A registered decoy profile.
     *
     * <p>Because {@link #credentialHash()} and {@link #salt()} are
     * {@code byte[]}, the record-generated {@link #equals(Object)} and
     * {@link #hashCode()} are identity-based for those components: two
     * {@code Registered} instances with byte-for-byte identical hash and
     * salt content are <em>not</em> {@code .equals()}. Callers that need
     * content-based comparison (e.g. change detection) must compare the
     * fields explicitly (e.g. via {@link java.util.Arrays#equals(byte[],
     * byte[])}) rather than relying on {@code .equals()}.
     */
    record Registered(String profileId, byte[] credentialHash, byte[] salt)
            implements DuressProfileState {
    }
}
