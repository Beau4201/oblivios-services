package org.sovereignos.airpods.validation;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        // This covers header-level garbage (truncation, bad length, bad opcode); it almost never
        // reaches type-specific validation, since a random byte string matches a valid opcode and
        // a matching declared length only with probability on the order of 1e-7. The two tests
        // below build structurally valid frames with randomized fields so the type-specific
        // bound checks (requiredPercent, ListeningMode range, requiredBoolean) are actually
        // exercised with adversarial values, not just skipped past.
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

    @Test
    void structuredFuzzExercisesTypeSpecificValidationBounds() {
        // Builds a real [opcode][length][payload] frame per iteration with a randomized payload
        // (and occasionally a corrupted length), so requiredPercent/ListeningMode-range/
        // requiredBoolean actually see out-of-range values, not just truncated headers.
        int[] opcodes = {0x01, 0x02, 0x03};
        int[] payloadLengths = {3, 1, 2};
        Random random = new Random(7);
        int rejections = 0;
        for (int i = 0; i < 2000; i++) {
            int choice = random.nextInt(opcodes.length);
            int opcode = opcodes[choice];
            int payloadLength = payloadLengths[choice];
            byte[] payload = new byte[payloadLength];
            random.nextBytes(payload);

            int declaredLength = random.nextInt(10) == 0
                ? payloadLength + 1 + random.nextInt(5)
                : payloadLength;

            byte[] frame = new byte[3 + payload.length];
            frame[0] = (byte) opcode;
            frame[1] = (byte) (declaredLength & 0xFF);
            frame[2] = (byte) ((declaredLength >> 8) & 0xFF);
            System.arraycopy(payload, 0, frame, 3, payload.length);

            try {
                parser.parse(frame);
            } catch (AirPodsValidationException expected) {
                rejections++;
            }
        }
        // With random payload bytes, out-of-range values (percent > 100 and != 0xFF, mode index
        // >= 4, wear flag not 0/1) are common; if this stayed 0 the bound checks aren't firing.
        assertTrue(rejections > 0, "expected at least some adversarial payloads to be rejected");
    }

    @Test
    void structuredFuzzWithUnknownOpcodeNeverThrowsUnbounded() {
        Random random = new Random(11);
        for (int i = 0; i < 500; i++) {
            int unknownOpcode = 4 + random.nextInt(252);
            int payloadLength = random.nextInt(8);
            byte[] payload = new byte[payloadLength];
            random.nextBytes(payload);

            byte[] frame = new byte[3 + payload.length];
            frame[0] = (byte) unknownOpcode;
            frame[1] = (byte) (payloadLength & 0xFF);
            frame[2] = (byte) ((payloadLength >> 8) & 0xFF);
            System.arraycopy(payload, 0, frame, 3, payload.length);

            try {
                parser.parse(frame);
            } catch (AirPodsValidationException expected) {
                // Expected: any opcode outside {0x01, 0x02, 0x03} is rejected.
            }
        }
    }
}
