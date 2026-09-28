package org.sovereignos.airpods.validation;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.sovereignos.airpods.validation.ListeningModeState.ListeningMode;

public final class AirPodsProtocolParser {
    private static final int OPCODE_BATTERY_REPORT = 0x01;
    private static final int OPCODE_LISTENING_MODE = 0x02;
    private static final int OPCODE_WEAR_STATE = 0x03;

    public AirPodsMessage parse(byte[] raw) {
        if (raw == null) {
            throw new AirPodsValidationException("AirPods message must not be null");
        }
        try {
            ByteBuffer buffer = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
            int opcode = Byte.toUnsignedInt(buffer.get());
            int length = Short.toUnsignedInt(buffer.getShort());
            if (length != buffer.remaining()) {
                throw new AirPodsValidationException(
                    "AirPods message declared length " + length + " does not match remaining bytes " + buffer.remaining());
            }
            return switch (opcode) {
                case OPCODE_BATTERY_REPORT -> parseBatteryReport(buffer);
                case OPCODE_LISTENING_MODE -> parseListeningMode(buffer);
                case OPCODE_WEAR_STATE -> parseWearState(buffer);
                default -> throw new AirPodsValidationException("Unknown AirPods message opcode " + opcode);
            };
        } catch (BufferUnderflowException exception) {
            throw new AirPodsValidationException("AirPods message truncated", exception);
        }
    }

    private BatteryReport parseBatteryReport(ByteBuffer buffer) {
        if (buffer.remaining() != 3) {
            throw new AirPodsValidationException("Battery report must be exactly 3 bytes, was " + buffer.remaining());
        }
        int left = requiredPercent(buffer.get());
        int right = requiredPercent(buffer.get());
        int caseValue = requiredPercent(buffer.get());
        return new BatteryReport(left, right, caseValue);
    }

    private static int requiredPercent(byte value) {
        int unsigned = Byte.toUnsignedInt(value);
        if (unsigned != 0xFF && unsigned > 100) {
            throw new AirPodsValidationException("Battery percent must be 0-100 or 0xFF (not present), was " + unsigned);
        }
        return unsigned;
    }

    private ListeningModeState parseListeningMode(ByteBuffer buffer) {
        if (buffer.remaining() != 1) {
            throw new AirPodsValidationException("Listening mode message must be exactly 1 byte, was " + buffer.remaining());
        }
        int mode = Byte.toUnsignedInt(buffer.get());
        ListeningMode[] values = ListeningMode.values();
        if (mode >= values.length) {
            throw new AirPodsValidationException("Unknown listening mode value " + mode);
        }
        return new ListeningModeState(values[mode]);
    }

    private WearState parseWearState(ByteBuffer buffer) {
        if (buffer.remaining() != 2) {
            throw new AirPodsValidationException("Wear state message must be exactly 2 bytes, was " + buffer.remaining());
        }
        boolean leftWorn = requiredBoolean(buffer.get());
        boolean rightWorn = requiredBoolean(buffer.get());
        return new WearState(leftWorn, rightWorn);
    }

    private static boolean requiredBoolean(byte value) {
        int unsigned = Byte.toUnsignedInt(value);
        if (unsigned != 0 && unsigned != 1) {
            throw new AirPodsValidationException("Wear flag must be 0 or 1, was " + unsigned);
        }
        return unsigned == 1;
    }
}
