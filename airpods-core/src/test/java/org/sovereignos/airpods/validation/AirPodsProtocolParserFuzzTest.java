package org.sovereignos.airpods.validation;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Random;
import org.junit.jupiter.api.Test;

final class AirPodsProtocolParserFuzzTest {
    private final AirPodsProtocolParser parser = new AirPodsProtocolParser();

    @Test
    void emptyInputIsRejectedNotCrashed() {
        assertThrows(AirPodsValidationException.class, () -> parser.parse(new byte[0]));
    }

    @Test
    void singleByteInputIsRejectedNotCrashed() {
        assertThrows(AirPodsValidationException.class, () -> parser.parse(new byte[] {0x01}));
    }

    @Test
    void declaredLengthExceedingRemainingBytesIsRejected() {
        // opcode=BATTERY_REPORT, declared length=200, but only 3 bytes actually follow.
        byte[] malformed = {0x01, (byte) 200, 0x00, 10, 20, 30};
        assertThrows(AirPodsValidationException.class, () -> parser.parse(malformed));
    }

    @Test
    void unknownOpcodeIsRejected() {
        byte[] malformed = {(byte) 0xFF, 0x00, 0x00};
        assertThrows(AirPodsValidationException.class, () -> parser.parse(malformed));
    }

    @Test
    void validBatteryReportParsesWithoutException() {
        byte[] valid = {0x01, 0x03, 0x00, 80, 75, 60};
        assertDoesNotThrow(() -> parser.parse(valid));
    }

    @Test
    void randomizedMalformedInputNeverThrowsUnboundedException() {
        // Any exception other than AirPodsValidationException escaping this loop fails the test.
        Random random = new Random(42);
        for (int i = 0; i < 2000; i++) {
            byte[] input = new byte[random.nextInt(20)];
            random.nextBytes(input);
            try {
                parser.parse(input);
            } catch (AirPodsValidationException expected) {
                // Bounded rejection is the correct outcome for malformed/truncated input.
            }
        }
    }
}
