package org.sovereignos.airpods.validation;

/** The value the pair reports for one of the {@link AirPodsControl} settings, always inside its range. */
public record ControlReport(AirPodsControl control, int value) implements AirPodsMessage {
    public ControlReport {
        if (control == null || !control.accepts(value)) {
            throw new AirPodsValidationException("control value " + value);
        }
    }
}
