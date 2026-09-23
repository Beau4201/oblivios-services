package org.sovereignos.duress.credential;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.security.SecureRandom;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class DuressCredentialHasherTest {

    @Test
    void hashingIsDeterministicForTheSameCredentialAndSalt() {
        byte[] salt = {1, 2, 3, 4};
        byte[] first = DuressCredentialHasher.hash("1234".toCharArray(), salt);
        byte[] second = DuressCredentialHasher.hash("1234".toCharArray(), salt);
        assertArrayEquals(first, second);
    }

    @Test
    void differentCredentialsProduceDifferentHashesForTheSameSalt() {
        byte[] salt = {1, 2, 3, 4};
        byte[] first = DuressCredentialHasher.hash("1234".toCharArray(), salt);
        byte[] second = DuressCredentialHasher.hash("5678".toCharArray(), salt);
        assertFalse(Arrays.equals(first, second));
    }

    @Test
    void differentSaltsProduceDifferentHashesForTheSameCredential() {
        byte[] first = DuressCredentialHasher.hash("1234".toCharArray(), new byte[]{1, 1, 1, 1});
        byte[] second = DuressCredentialHasher.hash("1234".toCharArray(), new byte[]{2, 2, 2, 2});
        assertFalse(Arrays.equals(first, second));
    }

    @Test
    void generatedSaltsHaveTheExpectedLengthAndAreNotConstant() {
        SecureRandom random = new SecureRandom();
        byte[] first = DuressCredentialHasher.generateSalt(random);
        byte[] second = DuressCredentialHasher.generateSalt(random);
        assertEquals(DuressCredentialHasher.SALT_LENGTH_BYTES, first.length);
        assertFalse(Arrays.equals(first, second));
    }
}
