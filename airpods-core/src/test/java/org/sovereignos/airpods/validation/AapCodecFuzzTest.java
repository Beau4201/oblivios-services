package org.sovereignos.airpods.validation;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import org.junit.jupiter.api.Test;

/** Any outcome other than a message or an AirPodsValidationException fails the test. */
final class AapCodecFuzzTest {
    private static void decodes(byte[] frame) {
        try {
            AirPodsMessage m = AapCodec.decode(frame);
            if (m instanceof BatteryStatus s) {
                for (BatteryStatus.Entry e : s.entries()) {
                    assertTrue(e.level() >= -1 && e.level() <= 100);
                }
            }
        } catch (AirPodsValidationException expected) {
            // bounded rejection is the right outcome for a malformed frame
        }
    }

    @Test
    void randomBytesNeverEscape() {
        Random r = new Random(7);
        for (int i = 0; i < 20000; i++) {
            byte[] f = new byte[r.nextInt(40)];
            r.nextBytes(f);
            decodes(f);
        }
    }

    @Test
    void randomFieldsInsideStructurallyValidFramesNeverEscape() {
        // A random byte string almost never carries the 04 00 04 00 header and a known opcode, so
        // build frames that do and randomise everything after it.
        Random r = new Random(11);
        int[] opcodes = {4, 6, 9};
        for (int i = 0; i < 50000; i++) {
            int length = 6 + r.nextInt(30);
            byte[] f = new byte[length];
            r.nextBytes(f);
            f[0] = 4;
            f[1] = 0;
            f[2] = 4;
            f[3] = 0;
            f[4] = (byte) opcodes[r.nextInt(opcodes.length)];
            f[5] = 0;
            if (f[4] == 4 && length > 6 && r.nextBoolean()) {
                f[6] = (byte) (1 + r.nextInt(3)); // a plausible component count
            }
            decodes(f);
        }
    }

    @Test
    void everySingleByteMutationOfAValidFrameIsHandled() {
        byte[] valid = {4, 0, 4, 0, 4, 0, 3, 4, 1, 100, 2, 1, 2, 1, 99, 1, 1, 8, 1, 17, 2, 1};
        for (int at = 0; at < valid.length; at++) {
            for (int v = 0; v < 256; v++) {
                byte[] f = valid.clone();
                f[at] = (byte) v;
                decodes(f);
            }
        }
    }
}
