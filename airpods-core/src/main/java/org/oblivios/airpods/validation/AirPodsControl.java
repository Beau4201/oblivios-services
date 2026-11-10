// SPDX-License-Identifier: Apache-2.0
package org.oblivios.airpods.validation;

import java.util.Optional;

/**
 * The comfort and sound settings of the pair that the own page offers. Each is one control of opcode
 * 0x0009: an identifier and one value byte. {@code min} and {@code max} bound the value we send and
 * accept. Plan 9 measured on the owner's pair (AIR-180, AIR-182) that a value of {@code PRESS_SPEED},
 * {@code PRESS_HOLD_DURATION}, {@code SWIPE_INTERVAL}, {@code SWIPE_MODE}, {@code GAIN_SWIPE},
 * {@code PERSONALIZED_VOLUME}, {@code SLEEP_DETECTION} and {@code CHIME_VOLUME} is written and read
 * back; the ranges of the others are the third-party notes (docs/research/airpods-aap-protocol-facts.md).
 */
public enum AirPodsControl {
    MICROPHONE(0x01, 0, 2),
    PRESS_SPEED(0x17, 0, 2),
    PRESS_HOLD_DURATION(0x18, 0, 2),
    CHIME_VOLUME(0x1F, 0, 100),
    CONNECT_AUTOMATICALLY(0x20, 1, 2),
    SWIPE_INTERVAL(0x23, 0, 2),
    SWIPE_MODE(0x25, 1, 2),
    PERSONALIZED_VOLUME(0x26, 1, 2),
    GAIN_SWIPE(0x2F, 0, 1),
    CASE_TONE(0x31, 1, 2),
    SLEEP_DETECTION(0x35, 1, 2),
    CASE_TONE_VOLUME(0x40, 0, 100);

    private final int identifier;
    private final int min;
    private final int max;

    AirPodsControl(int identifier, int min, int max) {
        this.identifier = identifier;
        this.min = min;
        this.max = max;
    }

    public int identifier() {
        return identifier;
    }

    public int min() {
        return min;
    }

    public int max() {
        return max;
    }

    public boolean accepts(int value) {
        return value >= min && value <= max;
    }

    public static Optional<AirPodsControl> ofIdentifier(int identifier) {
        for (AirPodsControl c : values()) {
            if (c.identifier == identifier) {
                return Optional.of(c);
            }
        }
        return Optional.empty();
    }
}
