package org.sovereignos.airpods.validation;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.sovereignos.airpods.contracts.AirPodsComponentId;
import org.sovereignos.airpods.validation.BatteryStatus.Charge;
import org.sovereignos.airpods.validation.ListeningModeState.ListeningMode;

final class AapCodecTest {
    private static byte[] hex(String s) {
        String t = s.replace(" ", "");
        byte[] out = new byte[t.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(t.substring(2 * i, 2 * i + 2), 16);
        }
        return out;
    }

    // The sample frame of the protocol facts record: left 100 % discharging, right 99 % charging,
    // case 17 % discharging.
    private static final String PRO_2_BATTERY = "04 00 04 00 04 00 03 04 01 64 02 01 02 01 63 01 01 08 01 11 02 01";

    @Test
    void decodesTheDocumentedBatteryFrame() {
        BatteryStatus s = assertInstanceOf(BatteryStatus.class, AapCodec.decode(hex(PRO_2_BATTERY)));
        assertEquals(
                List.of(
                        new BatteryStatus.Entry(AirPodsComponentId.LEFT_EARBUD, 100, Charge.DISCHARGING),
                        new BatteryStatus.Entry(AirPodsComponentId.RIGHT_EARBUD, 99, Charge.CHARGING),
                        new BatteryStatus.Entry(AirPodsComponentId.CASE, 17, Charge.DISCHARGING)),
                s.entries());
    }

    @Test
    void aDisconnectedComponentHasNoLevel() {
        BatteryStatus s = assertInstanceOf(BatteryStatus.class,
                AapCodec.decode(hex("04 00 04 00 04 00 01 04 01 FF 04 01")));
        assertEquals(new BatteryStatus.Entry(AirPodsComponentId.LEFT_EARBUD, -1, Charge.DISCONNECTED), s.entries().get(0));
    }

    @Test
    void decodesListeningModes() {
        assertEquals(new ListeningModeState(ListeningMode.OFF), AapCodec.decode(hex("04 00 04 00 09 00 0D 01 00 00 00")));
        assertEquals(new ListeningModeState(ListeningMode.ANC), AapCodec.decode(hex("04 00 04 00 09 00 0D 02 00 00 00")));
        assertEquals(new ListeningModeState(ListeningMode.TRANSPARENCY), AapCodec.decode(hex("04 00 04 00 09 00 0D 03 00 00 00")));
        assertEquals(new ListeningModeState(ListeningMode.ADAPTIVE), AapCodec.decode(hex("04 00 04 00 09 00 0D 04 00 00 00")));
    }

    @Test
    void decodesSettingsAndEarState() {
        assertEquals(new ConversationAwarenessSetting(true), AapCodec.decode(hex("04 00 04 00 09 00 28 01 00 00 00")));
        assertEquals(new ConversationAwarenessSetting(false), AapCodec.decode(hex("04 00 04 00 09 00 28 02 00 00 00")));
        assertEquals(new EarDetectionSetting(true), AapCodec.decode(hex("04 00 04 00 09 00 0A 01 00 00 00")));
        assertEquals(new EarDetectionSetting(false), AapCodec.decode(hex("04 00 04 00 09 00 0A 02 00 00 00")));
        assertEquals(new EarState(EarState.Placement.IN_EAR, EarState.Placement.IN_CASE),
                AapCodec.decode(hex("04 00 04 00 06 00 00 02")));
    }

    @Test
    void encodesTheDocumentedFrames() {
        assertArrayEquals(hex("00 00 04 00 01 00 02 00 00 00 00 00 00 00 00 00"), AapCodec.handshake());
        assertArrayEquals(hex("04 00 04 00 0F 00 FF FF FF FF"), AapCodec.requestNotifications());
        assertArrayEquals(hex("04 00 04 00 4D 00 FF 00 00 00 00 00 00 00"), AapCodec.hostCapabilities());
        assertArrayEquals(hex("04 00 04 00 09 00 0D 01 00 00 00"), AapCodec.setListeningMode(ListeningMode.OFF));
        assertArrayEquals(hex("04 00 04 00 09 00 0D 02 00 00 00"), AapCodec.setListeningMode(ListeningMode.ANC));
        assertArrayEquals(hex("04 00 04 00 09 00 0D 03 00 00 00"), AapCodec.setListeningMode(ListeningMode.TRANSPARENCY));
        assertArrayEquals(hex("04 00 04 00 09 00 28 01 00 00 00"), AapCodec.setConversationAwareness(true));
        assertArrayEquals(hex("04 00 04 00 09 00 28 02 00 00 00"), AapCodec.setConversationAwareness(false));
        assertArrayEquals(hex("04 00 04 00 09 00 0A 01 00 00 00"), AapCodec.setEarDetection(true));
        assertArrayEquals(hex("04 00 04 00 09 00 0A 02 00 00 00"), AapCodec.setEarDetection(false));
        assertArrayEquals(hex("04 00 04 00 09 00 34 01 00 00 00"), AapCodec.setAllowOffOption(true));
        assertArrayEquals(hex("04 00 04 00 09 00 34 02 00 00 00"), AapCodec.setAllowOffOption(false));
    }

    @Test
    void whatWeSendIsWhatWeDecode() {
        for (ListeningMode m : ListeningMode.values()) {
            assertEquals(new ListeningModeState(m), AapCodec.decode(AapCodec.setListeningMode(m)));
        }
        for (boolean b : new boolean[] {true, false}) {
            assertEquals(new ConversationAwarenessSetting(b), AapCodec.decode(AapCodec.setConversationAwareness(b)));
            assertEquals(new EarDetectionSetting(b), AapCodec.decode(AapCodec.setEarDetection(b)));
        }
    }

    @Test
    void encodersReturnACopyEachTime() {
        byte[] a = AapCodec.handshake();
        a[0] = 99;
        assertEquals(0, AapCodec.handshake()[0]);
    }

    @Test
    void unknownKindsAreReportedNotActedOn() {
        assertEquals(new UnrecognisedMessage(0x1D, -1), AapCodec.decode(hex("04 00 04 00 1D 00 00 02 D5 00")));
        assertEquals(new UnrecognisedMessage(9, 0x2E), AapCodec.decode(hex("04 00 04 00 09 00 2E 32 00 00 00")));
        assertEquals(new UnrecognisedMessage(-1, -1), AapCodec.decode(hex("01 00 04 00 00 00 00 00")));
        assertEquals(new UnrecognisedMessage(-1, -1), AapCodec.decode(new byte[0]));
        assertEquals(new UnrecognisedMessage(-1, -1), AapCodec.decode(hex("04 00 04 00 04")));
    }

    @Test
    void malformedFramesOfAKnownKindAreRejected() {
        String[] bad = {
            "04 00 04 00 04 00",                                          // truncated
            "04 00 04 00 04 00 00",                                       // zero components
            "04 00 04 00 04 00 04 04 01 64 02 01 02 01 63 01 01 08 01 11 02 01 02 01 01 01 01", // four
            "04 00 04 00 04 00 03 04 01 64 02 01 02 01 63 01 01 08 01 11 02",                    // short
            "04 00 04 00 04 00 03 04 01 64 02 01 02 01 63 01 01 08 01 11 02 01 00",              // long
            "04 00 04 00 04 00 01 05 01 64 02 01",                        // component 5
            "04 00 04 00 04 00 01 04 00 64 02 01",                        // spacer
            "04 00 04 00 04 00 01 04 01 64 02 00",                        // end marker
            "04 00 04 00 04 00 01 04 01 65 02 01",                        // level 101
            "04 00 04 00 04 00 01 04 01 64 03 01",                        // status 3
            "04 00 04 00 04 00 02 04 01 64 02 01 04 01 50 02 01",         // component twice
            "04 00 04 00 06 00 00",                                       // ear state short
            "04 00 04 00 06 00 00 03",                                    // placement 3
            "04 00 04 00 09 00 0D 05 00 00 00",                           // mode 5
            "04 00 04 00 09 00 0D 00 00 00 00",                           // mode 0
            "04 00 04 00 09 00 28 03 00 00 00",                           // setting 3
            "04 00 04 00 09 00 0A 00 00 00 00",                           // setting 0
            "04 00 04 00 09 00 0D 02 00 00",                              // control short
            "04 00 04 00 09 00 0D 02 00 00 00 00",                        // control long
        };
        for (String b : bad) {
            assertThrows(AirPodsValidationException.class, () -> AapCodec.decode(hex(b)), b);
        }
    }

    @Test
    void anOversizedFrameIsRefusedBeforeAnythingIsRead() {
        assertThrows(AirPodsValidationException.class, () -> AapCodec.decode(new byte[AapCodec.MAX_FRAME + 1]));
        assertThrows(AirPodsValidationException.class, () -> AapCodec.decode(null));
    }
}
