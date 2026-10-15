// SPDX-License-Identifier: Apache-2.0
package org.freeholdos.guardian.platform;

import java.util.Map;

public record PlatformSnapshot(String packageName, Map<String, String> state) {
    public PlatformSnapshot {
        state = Map.copyOf(state);
    }
}
