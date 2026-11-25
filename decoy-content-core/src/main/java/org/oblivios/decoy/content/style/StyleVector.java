// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.style;

/**
 * How one writer texts: register, capitals, punctuation, emoji per message, messages per burst, message length,
 * typos per message and the share of voice messages.
 */
public record StyleVector(Register register, Caps caps, Punctuation punctuation, double emojiRate, int burst, Length length,
        double typoRate, double voiceRate) {

    /** From most to least formal; {@code STREET} is the register of street slang. */
    public enum Register { FORMAL, NEUTRAL, CASUAL, STREET }

    public enum Caps { STANDARD, LOWER, MIXED }

    public enum Punctuation { FULL, LIGHT, NONE }

    public enum Length { SHORT, MEDIUM, LONG }

    public StyleVector {
        if (register == null || caps == null || punctuation == null || length == null) {
            throw new IllegalArgumentException("a style has no empty field");
        }
        if (emojiRate < 0 || typoRate < 0 || voiceRate < 0 || voiceRate > 1 || burst < 1) {
            throw new IllegalArgumentException("style rate out of range: emoji " + emojiRate + ", typos " + typoRate
                    + ", voice " + voiceRate + ", burst " + burst);
        }
    }

    public StyleVector withRegister(Register r) {
        return new StyleVector(r, caps, punctuation, emojiRate, burst, length, typoRate, voiceRate);
    }

    public StyleVector withVoiceRate(double v) {
        return new StyleVector(register, caps, punctuation, emojiRate, burst, length, typoRate, v);
    }
}
