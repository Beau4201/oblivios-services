// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.SplittableRandom;
import java.util.random.RandomGenerator;

/**
 * The one seed of a decoy (ADR-315 (7)): every generator draws from a named stream, so adding a
 * draw in one surface never shifts the values of another.
 */
public final class Seed {
    private final long a;
    private final long b;

    private Seed(long a, long b) {
        this.a = a;
        this.b = b;
    }

    public static Seed random() {
        SecureRandom r = new SecureRandom();
        return new Seed(r.nextLong(), r.nextLong());
    }

    public static Seed of(long a, long b) {
        return new Seed(a, b);
    }

    /** A fresh generator for {@code name}; the same seed and name always give the same sequence. */
    public RandomGenerator stream(String name) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            sha.update(ByteBuffer.allocate(16).putLong(a).putLong(b).array());
            sha.update(name.getBytes(StandardCharsets.UTF_8));
            return new SplittableRandom(ByteBuffer.wrap(sha.digest()).getLong());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e); // SHA-256 is mandatory on every JRE
        }
    }
}
