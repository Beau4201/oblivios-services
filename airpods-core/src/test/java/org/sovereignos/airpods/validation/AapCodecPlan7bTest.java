// SPDX-License-Identifier: Apache-2.0
package org.sovereignos.airpods.validation;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.sovereignos.airpods.validation.ListeningModeState.ListeningMode;

/**
 * The frames of Plan 7B (captured on the owner's pair on 2026-10-04, AIR-161 to AIR-164). The device
 * information frame of the capture had 241 bytes; the vectors here keep its shape (the field order and
 * lengths) with invented names and serial numbers.
 */
final class AapCodecPlan7bTest {
    private static byte[] hex(String s) {
        String t = s.replace(" ", "");
        byte[] out = new byte[t.length() / 2];
        for (int i = 0; i < out.length; i++) out[i] = (byte) Integer.parseInt(t.substring(2 * i, 2 * i + 2), 16);
        return out;
    }

    /** Header, two bytes before the first string, the fields NUL-terminated, then a non-printable tail. */
    private static byte[] deviceInformation(String... fields) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(hex("04 00 04 00 1D 00 01 00"));
        for (String f : fields) {
            out.writeBytes(f.getBytes(StandardCharsets.UTF_8));
            out.write(0);
        }
        out.writeBytes(hex("00 01 00 02 80 90 A0 B0"));
        return out.toByteArray();
    }

    private static final String[] CAPTURE_SHAPE = {
        "Test AirPods", "A3047", "Apple Inc.", "SERIAL0001",
        "90.3431000025000000.6807", "90.3431000025000000.6807", "1.0.0",
        "com.apple.accessory.updater.app.71", "LEFTSERIAL01", "RIGHTSERIAL1", "9442752", "1", "1", "12"
    };

    @Test
    void encodesTheAdaptiveLevel() {
        assertArrayEquals(hex("04 00 04 00 09 00 2E 32 00 00 00"), AapCodec.setAdaptiveLevel(50));
        assertArrayEquals(hex("04 00 04 00 09 00 2E 00 00 00 00"), AapCodec.setAdaptiveLevel(0));
        assertArrayEquals(hex("04 00 04 00 09 00 2E 64 00 00 00"), AapCodec.setAdaptiveLevel(100));
        assertThrows(IllegalArgumentException.class, () -> AapCodec.setAdaptiveLevel(-1));
        assertThrows(IllegalArgumentException.class, () -> AapCodec.setAdaptiveLevel(101));
    }

    @Test
    void encodesThePressAndHoldMask() {
        Set<ListeningMode> nc = EnumSet.of(ListeningMode.ANC, ListeningMode.TRANSPARENCY);
        assertArrayEquals(hex("04 00 04 00 09 00 1A 06 00 00 00"), AapCodec.setPressAndHoldModes(nc));
        assertArrayEquals(hex("04 00 04 00 09 00 1A 0F 00 00 00"), AapCodec.setPressAndHoldModes(EnumSet.allOf(ListeningMode.class)));
        assertArrayEquals(hex("04 00 04 00 09 00 1A 09 00 00 00"),
                AapCodec.setPressAndHoldModes(EnumSet.of(ListeningMode.OFF, ListeningMode.ADAPTIVE)));
        assertThrows(IllegalArgumentException.class, () -> AapCodec.setPressAndHoldModes(EnumSet.noneOf(ListeningMode.class)));
        assertThrows(IllegalArgumentException.class, () -> AapCodec.setPressAndHoldModes(EnumSet.of(ListeningMode.ANC)));
        assertThrows(IllegalArgumentException.class, () -> AapCodec.setPressAndHoldModes(null));
    }

    @Test
    void encodesTheRenameFrameOfTheCapture() {
        // Sent to the owner's pair on 2026-10-04; the name field of the next session had length 13.
        assertArrayEquals(hex("04 00 04 00 1A 00 01 0D 00 41 69 72 50 6F 64 73 20 50 72 6F 62 65"), AapCodec.rename("AirPods Probe"));
        assertArrayEquals(hex("04 00 04 00 1A 00 01 05 00 43 61 66 C3 A9"), AapCodec.rename("Café"));
    }

    @Test
    void acceptsOnlyReasonableNames() {
        assertTrue(AapCodec.isValidName("A"));
        assertTrue(AapCodec.isValidName("a".repeat(AapCodec.MAX_NAME_BYTES)));
        assertTrue(AapCodec.isValidName("Two  spaces inside"));
        assertFalse(AapCodec.isValidName(null));
        assertFalse(AapCodec.isValidName(""));
        assertFalse(AapCodec.isValidName("   "));
        assertFalse(AapCodec.isValidName(" leading"));
        assertFalse(AapCodec.isValidName("trailing "));
        assertFalse(AapCodec.isValidName("a".repeat(AapCodec.MAX_NAME_BYTES + 1)));
        assertFalse(AapCodec.isValidName("é".repeat(17)), "34 bytes");
        assertFalse(AapCodec.isValidName("tab\there"));
        assertFalse(AapCodec.isValidName("nul\u0000"));
        assertFalse(AapCodec.isValidName("bidi‮evil"), "format character");
        assertFalse(AapCodec.isValidName("line break"));
        assertFalse(AapCodec.isValidName("lone\uD800surrogate"));
        assertFalse(AapCodec.isValidName(" "), "only a no-break space");
        assertFalse(AapCodec.isValidName("name "), "trailing no-break space");
        assertFalse(AapCodec.isValidName(" name"), "leading no-break space");
        assertFalse(AapCodec.isValidName("ㅤ"), "Hangul filler");
        assertFalse(AapCodec.isValidName("⠀"), "Braille blank");
        assertFalse(AapCodec.isValidName("aㅤb"), "a filler inside");
        assertTrue(AapCodec.isValidName("Beau’s AirPods"), "a visible apostrophe and an inner space stay valid");
        assertFalse(AapCodec.isValidName("privateuse"));
        assertThrows(IllegalArgumentException.class, () -> AapCodec.rename("tab\there"));
        assertThrows(IllegalArgumentException.class, () -> AapCodec.rename(""));
    }

    @Test
    void decodesTheDeviceInformationShapeOfTheCapture() {
        AirPodsMessage m = AapCodec.decode(deviceInformation(CAPTURE_SHAPE));
        assertEquals(new DeviceInformation("A3047", "90.3431000025000000.6807"), m);
    }

    @Test
    void neverKeepsTheNameOrASerialNumber() {
        String printed = AapCodec.decode(deviceInformation(CAPTURE_SHAPE)).toString();
        assertFalse(printed.contains("SERIAL"), printed);
        assertFalse(printed.contains("Test AirPods"), printed);
    }

    @Test
    void aNonAsciiNameSplitsTheFirstFieldButTheAnchorHolds() {
        String[] f = CAPTURE_SHAPE.clone();
        f[0] = "Café AirPods";
        assertEquals(new DeviceInformation("A3047", "90.3431000025000000.6807"), AapCodec.decode(deviceInformation(f)));
    }

    @Test
    void aNameEqualToTheVendorDoesNotMoveTheAnchor() {
        String[] f = CAPTURE_SHAPE.clone();
        f[0] = "Apple Inc.";
        assertEquals(new DeviceInformation("A3047", "90.3431000025000000.6807"), AapCodec.decode(deviceInformation(f)));
    }

    @Test
    void aFrameWhoseLayoutIsNotOursIsUnrecognisedNotAnError() {
        UnrecognisedMessage unknown = new UnrecognisedMessage(0x1D, -1);
        assertEquals(unknown, AapCodec.decode(hex("04 00 04 00 1D 00 00 02 D5 00")));
        String[] noVendor = CAPTURE_SHAPE.clone();
        noVendor[2] = "Some Vendor";
        assertEquals(unknown, AapCodec.decode(deviceInformation(noVendor)));
        String[] badModel = CAPTURE_SHAPE.clone();
        badModel[1] = "B3047";
        assertEquals(unknown, AapCodec.decode(deviceInformation(badModel)));
        String[] longModel = CAPTURE_SHAPE.clone();
        longModel[1] = "A30470";
        assertEquals(unknown, AapCodec.decode(deviceInformation(longModel)));
        String[] badFirmware = CAPTURE_SHAPE.clone();
        badFirmware[4] = "not a version!";
        assertEquals(unknown, AapCodec.decode(deviceInformation(badFirmware)));
        String[] longFirmware = CAPTURE_SHAPE.clone();
        longFirmware[4] = "1".repeat(33);
        assertEquals(unknown, AapCodec.decode(deviceInformation(longFirmware)));
        String[] vendorLast = {"Name", "A3047", "Apple Inc."};
        assertEquals(unknown, AapCodec.decode(deviceInformation(vendorLast)));
    }

    @Test
    void aFieldLongerThanTheBoundIsSkippedNotKept() {
        String[] f = CAPTURE_SHAPE.clone();
        f[0] = "n".repeat(100);
        assertEquals(new DeviceInformation("A3047", "90.3431000025000000.6807"), AapCodec.decode(deviceInformation(f)));
    }

    @Test
    void theRecordRejectsWhatTheDecoderWouldNot() {
        assertThrows(AirPodsValidationException.class, () -> new DeviceInformation("B3047", "1"));
        assertThrows(AirPodsValidationException.class, () -> new DeviceInformation("A3047", ""));
        assertThrows(AirPodsValidationException.class, () -> new DeviceInformation("A3047", null));
        assertThrows(AirPodsValidationException.class, () -> new DeviceInformation(null, "1"));
    }

    @Test
    void randomDeviceInformationFramesNeverEscape() {
        Random r = new Random(29);
        for (int i = 0; i < 30000; i++) {
            byte[] f = new byte[6 + r.nextInt(300)];
            r.nextBytes(f);
            f[0] = 4;
            f[1] = 0;
            f[2] = 4;
            f[3] = 0;
            f[4] = 0x1D;
            f[5] = 0;
            AirPodsMessage m = AapCodec.decode(f);
            assertTrue(m instanceof DeviceInformation || m instanceof UnrecognisedMessage, "unexpected " + m);
        }
    }

    @Test
    void everySingleByteMutationOfADeviceInformationFrameIsHandled() {
        byte[] base = deviceInformation(CAPTURE_SHAPE);
        for (int i = 0; i < base.length; i++) {
            for (int v : new int[] {0x00, 0x20, 0x41, 0x7E, 0x7F, 0xFF}) {
                byte[] f = base.clone();
                f[i] = (byte) v;
                try {
                    assertTrue(AapCodec.decode(f) != null);
                } catch (AirPodsValidationException expected) {
                    // a bounded rejection is the right outcome for a malformed frame of a known kind
                }
            }
        }
    }
}
