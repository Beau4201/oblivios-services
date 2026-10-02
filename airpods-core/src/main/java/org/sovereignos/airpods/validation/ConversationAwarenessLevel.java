package org.sovereignos.airpods.validation;

/**
 * The AirPods' report of how far a conversation has got (opcode 0x004B, ADR-125). Seen on the owner's
 * pair while speaking with music playing: 01 and 02 when speech starts, 03 to 05 while it goes on, 0B
 * and 04 near the end, 08 and 09 when it is over; 09 is also the idle value. This record only carries
 * the byte (0 to 255); what to do with it is the host's decision.
 */
public record ConversationAwarenessLevel(int level) implements AirPodsMessage {
    public ConversationAwarenessLevel {
        if (level < 0 || level > 255) {
            throw new AirPodsValidationException("conversation awareness level " + level);
        }
    }
}
