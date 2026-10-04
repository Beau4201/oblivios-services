package org.sovereignos.airpods.validation;

/**
 * The call management setting (control 0x24): two data bytes. The owner's pair reports 00 03; what each
 * byte means is measured in Plan 9 task 2 (AIR-186), not assumed here.
 */
public record CallManagementReport(int first, int second) implements AirPodsMessage {
    public CallManagementReport {
        if (first < 0 || first > 255 || second < 0 || second > 255) {
            throw new AirPodsValidationException("call management " + first + " " + second);
        }
    }
}
