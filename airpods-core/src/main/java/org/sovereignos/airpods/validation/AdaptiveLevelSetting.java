package org.sovereignos.airpods.validation;

/**
 * The adaptive-mode strength (control identifier 0x2E), 0 to 100, 50 in the middle. Pairs that have
 * adaptive mode report it in the connection burst, so its presence is the evidence that the mode
 * exists. Writes produced no report back on the owner's pair, so the app never writes it (ADR-126).
 */
public record AdaptiveLevelSetting(int percent) implements AirPodsMessage {
    public AdaptiveLevelSetting {
        if (percent < 0 || percent > 100) {
            throw new AirPodsValidationException("adaptive level " + percent);
        }
    }
}
