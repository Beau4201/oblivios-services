// SPDX-License-Identifier: Apache-2.0
package org.oblivios.guardian.evidence;

public record ResolvedAppIdentity(String appLabel, String iconKey) {
    public ResolvedAppIdentity {
        if (appLabel == null || appLabel.isBlank()) {
            throw new IllegalArgumentException("Resolved app identity requires a non-blank label.");
        }
        if (iconKey == null || iconKey.isBlank()) {
            throw new IllegalArgumentException("Resolved app identity requires a non-blank icon key.");
        }
    }
}
