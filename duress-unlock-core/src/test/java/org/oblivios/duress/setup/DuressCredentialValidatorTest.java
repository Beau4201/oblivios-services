// SPDX-License-Identifier: Apache-2.0
package org.oblivios.duress.setup;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DuressCredentialValidatorTest {

    @Test
    void differentCredentialsAreValid() {
        boolean valid = DuressCredentialValidator.isValidDecoyCredential(
                "5678".toCharArray(), "1234".toCharArray());
        assertTrue(valid);
    }

    @Test
    void identicalCredentialsAreRejected() {
        boolean valid = DuressCredentialValidator.isValidDecoyCredential(
                "1234".toCharArray(), "1234".toCharArray());
        assertFalse(valid);
    }
}
