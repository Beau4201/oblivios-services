package org.sovereignos.airpods.validation;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.sovereignos.airpods.contracts.AirPodsComponentId;
import org.sovereignos.airpods.validation.ListeningModeState.ListeningMode;

/**
 * Encodes the few frames we send and decodes the frames we act on, for the AirPods accessory
 * protocol on L2CAP PSM 0x1001. Every decoded value is range-checked here once; a malformed frame
 * of a known kind throws {@link AirPodsValidationException}, a frame of an unknown kind is returned
 * as {@link UnrecognisedMessage}. Byte layouts: docs/research/airpods-aap-protocol-facts.md.
 * Nothing in the output of an encoder depends on bytes received from the device.
 */
public final class AapCodec {
    /** Frames longer than this are refused (the longest frame we act on is 22 bytes). */
    public static final int MAX_FRAME = 1024;

    private static final int HEADER_LENGTH = 4;
    private static final int OPCODE_BATTERY = 0x0004;
    private static final int OPCODE_EAR_STATE = 0x0006;
    private static final int OPCODE_CONTROL = 0x0009;
    private static final int OPCODE_CONVERSATION_LEVEL = 0x004B;
    private static final int CONTROL_FRAME_LENGTH = 11;
    private static final int ID_EAR_DETECTION = 0x0A;
    private static final int ID_ALLOW_OFF_OPTION = 0x34;
    private static final int ID_LISTENING_MODE = 0x0D;
    private static final int ID_CONVERSATION_AWARENESS = 0x28;
    private static final int ID_ADAPTIVE_LEVEL = 0x2E;

    private AapCodec() {}

    public static byte[] handshake() {
        return new byte[] {0, 0, 4, 0, 1, 0, 2, 0, 0, 0, 0, 0, 0, 0, 0, 0};
    }

    /** Host capability flags the iPhone and Mac send; without them conversation awareness stops working while audio plays. */
    public static byte[] hostCapabilities() {
        return new byte[] {4, 0, 4, 0, 0x4D, 0, (byte) 0xFF, 0, 0, 0, 0, 0, 0, 0};
    }

    public static byte[] requestNotifications() {
        return new byte[] {4, 0, 4, 0, 0x0F, 0, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF};
    }

    public static byte[] setListeningMode(ListeningMode mode) {
        if (mode == null) {
            throw new IllegalArgumentException("mode");
        }
        return control(ID_LISTENING_MODE, switch (mode) {
            case OFF -> 1;
            case ANC -> 2;
            case TRANSPARENCY -> 3;
            case ADAPTIVE -> 4;
        });
    }

    public static byte[] setConversationAwareness(boolean enabled) {
        return control(ID_CONVERSATION_AWARENESS, enabled ? 1 : 2);
    }

    /**
     * "Allow Off option for the listening mode": a pair that has it disabled refuses a request for
     * off and answers with another mode (seen on the owner's pair, AIR-123); with it enabled the same
     * request is accepted. The AirPods answer this frame with the current listening mode.
     */
    public static byte[] setAllowOffOption(boolean enabled) {
        return control(ID_ALLOW_OFF_OPTION, enabled ? 1 : 2);
    }

    public static byte[] setEarDetection(boolean enabled) {
        return control(ID_EAR_DETECTION, enabled ? 1 : 2);
    }

    private static byte[] control(int identifier, int value) {
        return new byte[] {4, 0, 4, 0, 9, 0, (byte) identifier, (byte) value, 0, 0, 0};
    }

    public static AirPodsMessage decode(byte[] frame) {
        if (frame == null) {
            throw new AirPodsValidationException("frame must not be null");
        }
        if (frame.length > MAX_FRAME) {
            throw new AirPodsValidationException("frame longer than " + MAX_FRAME + " bytes");
        }
        if (frame.length < HEADER_LENGTH + 2
                || frame[0] != 4 || frame[1] != 0 || frame[2] != 4 || frame[3] != 0) {
            // Too short for an opcode, or not the standard header (the handshake answer, if any,
            // is not documented): nothing here is acted on.
            return new UnrecognisedMessage(-1, -1);
        }
        int opcode = u8(frame[4]) | (u8(frame[5]) << 8);
        return switch (opcode) {
            case OPCODE_BATTERY -> decodeBattery(frame);
            case OPCODE_EAR_STATE -> decodeEarState(frame);
            case OPCODE_CONTROL -> decodeControl(frame);
            case OPCODE_CONVERSATION_LEVEL -> decodeConversationLevel(frame);
            default -> new UnrecognisedMessage(opcode, -1);
        };
    }

    private static AirPodsMessage decodeBattery(byte[] f) {
        if (f.length < 7) {
            throw new AirPodsValidationException("battery frame truncated");
        }
        int count = u8(f[6]);
        if (count < 1 || count > 3) {
            throw new AirPodsValidationException("battery component count " + count);
        }
        if (f.length != 7 + 5 * count) {
            throw new AirPodsValidationException("battery frame length " + f.length + " for " + count + " components");
        }
        List<BatteryStatus.Entry> entries = new ArrayList<>(count);
        Set<AirPodsComponentId> seen = EnumSet.noneOf(AirPodsComponentId.class);
        for (int i = 0; i < count; i++) {
            int at = 7 + 5 * i;
            AirPodsComponentId component = switch (u8(f[at])) {
                case 4 -> AirPodsComponentId.LEFT_EARBUD;
                case 2 -> AirPodsComponentId.RIGHT_EARBUD;
                case 8 -> AirPodsComponentId.CASE;
                default -> throw new AirPodsValidationException("battery component " + u8(f[at]));
            };
            if (!seen.add(component)) {
                throw new AirPodsValidationException("battery component repeated");
            }
            if (u8(f[at + 1]) != 1 || u8(f[at + 4]) != 1) {
                throw new AirPodsValidationException("battery entry markers");
            }
            BatteryStatus.Charge charge = switch (u8(f[at + 3])) {
                case 0 -> BatteryStatus.Charge.UNKNOWN;
                case 1 -> BatteryStatus.Charge.CHARGING;
                case 2 -> BatteryStatus.Charge.DISCHARGING;
                case 4 -> BatteryStatus.Charge.DISCONNECTED;
                default -> throw new AirPodsValidationException("battery status " + u8(f[at + 3]));
            };
            int level = u8(f[at + 2]);
            if (charge == BatteryStatus.Charge.DISCONNECTED) {
                level = -1;
            } else if (level > 100) {
                throw new AirPodsValidationException("battery level " + level);
            }
            entries.add(new BatteryStatus.Entry(component, level, charge));
        }
        return new BatteryStatus(entries);
    }

    private static AirPodsMessage decodeEarState(byte[] f) {
        if (f.length != 8) {
            throw new AirPodsValidationException("ear state frame length " + f.length);
        }
        return new EarState(placement(f[6]), placement(f[7]));
    }

    private static EarState.Placement placement(byte b) {
        return switch (u8(b)) {
            case 0 -> EarState.Placement.IN_EAR;
            case 1 -> EarState.Placement.OUT_OF_EAR;
            case 2 -> EarState.Placement.IN_CASE;
            default -> throw new AirPodsValidationException("pod placement " + u8(b));
        };
    }

    private static AirPodsMessage decodeControl(byte[] f) {
        if (f.length != CONTROL_FRAME_LENGTH) {
            throw new AirPodsValidationException("control frame length " + f.length);
        }
        int identifier = u8(f[6]);
        int value = u8(f[7]);
        return switch (identifier) {
            case ID_LISTENING_MODE -> new ListeningModeState(switch (value) {
                case 1 -> ListeningMode.OFF;
                case 2 -> ListeningMode.ANC;
                case 3 -> ListeningMode.TRANSPARENCY;
                case 4 -> ListeningMode.ADAPTIVE;
                default -> throw new AirPodsValidationException("listening mode " + value);
            });
            case ID_EAR_DETECTION -> new EarDetectionSetting(enabled(value));
            case ID_CONVERSATION_AWARENESS -> new ConversationAwarenessSetting(enabled(value));
            case ID_ADAPTIVE_LEVEL -> new AdaptiveLevelSetting(percent(value));
            default -> new UnrecognisedMessage(OPCODE_CONTROL, identifier);
        };
    }

    private static AirPodsMessage decodeConversationLevel(byte[] f) {
        // 04 00 04 00 4B 00 | 02 00 01 | level. The three bytes in the middle were identical in every
        // captured frame; a frame of another shape is not one we act on.
        if (f.length != 10 || f[6] != 2 || f[7] != 0 || f[8] != 1) {
            throw new AirPodsValidationException("conversation awareness frame");
        }
        return new ConversationAwarenessLevel(u8(f[9]));
    }

    private static int percent(int value) {
        if (value > 100) {
            throw new AirPodsValidationException("adaptive level " + value);
        }
        return value;
    }

    private static boolean enabled(int value) {
        if (value != 1 && value != 2) {
            throw new AirPodsValidationException("setting value " + value);
        }
        return value == 1;
    }

    private static int u8(byte b) {
        return Byte.toUnsignedInt(b);
    }
}
