package org.sovereignos.airpods.validation;

/**
 * A well-formed frame this codec does not act on. Only the numbers are kept, never the payload
 * (device information frames carry serial numbers). {@code opcode} is -1 for a frame without the
 * standard header, {@code identifier} is -1 when the frame is not a control frame.
 */
public record UnrecognisedMessage(int opcode, int identifier) implements AirPodsMessage {
}
