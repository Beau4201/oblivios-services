// SPDX-License-Identifier: Apache-2.0
package org.freeholdos.airpods.validation;

public final class AirPodsValidationException extends RuntimeException {
    public AirPodsValidationException(String message) {
        super(message);
    }

    public AirPodsValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
