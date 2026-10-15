// SPDX-License-Identifier: Apache-2.0
package org.freeholdos.duress.credential;

import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * PBKDF2-HMAC-SHA256 hashing for duress (decoy) credentials.
 *
 * <p>This class never mutates or zeroizes caller-supplied {@code char[]}
 * credential arrays. {@link #hash(char[], byte[])} only clears its own
 * internal {@link PBEKeySpec} clone (via {@link PBEKeySpec#clearPassword()})
 * before returning. The caller retains ownership of the {@code credential}
 * array passed in and is responsible for wiping it after use.
 */
public final class DuressCredentialHasher {

    static final int SALT_LENGTH_BYTES = 16;
    // Conservative PBKDF2-HMAC-SHA256 iteration count; deliberately far
    // above what a single interactive unlock attempt needs to feel instant,
    // since this cost also has to be paid symmetrically on every attempt
    // that does NOT match a registered decoy (see DuressCredentialMatcher).
    private static final int ITERATIONS = 210_000;
    private static final int KEY_LENGTH_BITS = 256;
    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";

    private DuressCredentialHasher() {
    }

    public static byte[] generateSalt(SecureRandom random) {
        byte[] salt = new byte[SALT_LENGTH_BYTES];
        random.nextBytes(salt);
        return salt;
    }

    public static byte[] hash(char[] credential, byte[] salt) {
        PBEKeySpec spec = new PBEKeySpec(credential, salt, ITERATIONS, KEY_LENGTH_BITS);
        try {
            SecretKeyFactory factory = SecretKeyFactory.getInstance(ALGORITHM);
            return factory.generateSecret(spec).getEncoded();
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new IllegalStateException("PBKDF2WithHmacSHA256 unavailable", e);
        } finally {
            spec.clearPassword();
        }
    }
}
