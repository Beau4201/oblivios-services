package org.sovereignos.airpods.validation;

import java.util.List;
import org.sovereignos.airpods.contracts.AirPodsComponentId;

/** Battery of the components named in one frame (a component that is not named is unchanged). */
public record BatteryStatus(List<Entry> entries) implements AirPodsMessage {
    public enum Charge {
        UNKNOWN,
        CHARGING,
        DISCHARGING,
        DISCONNECTED
    }

    /** {@code level} is 0..100, or -1 when the component is disconnected. */
    public record Entry(AirPodsComponentId component, int level, Charge charge) {
        public Entry {
            if (level < -1 || level > 100) {
                throw new IllegalArgumentException("level out of range");
            }
        }
    }

    public BatteryStatus {
        entries = List.copyOf(entries);
    }
}
