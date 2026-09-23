package org.sovereignos.guardian.platform;

import java.util.Map;

public record PlatformSnapshot(String packageName, Map<String, String> state) {
    public PlatformSnapshot {
        state = Map.copyOf(state);
    }
}
