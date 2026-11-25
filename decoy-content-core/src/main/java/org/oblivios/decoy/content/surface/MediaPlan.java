// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.surface;

import java.util.List;

/** The gallery of the decoy, ordered by capture time; {@code warnings} name what the plan had to drop or default. */
public record MediaPlan(List<MediaItem> items, List<String> warnings) {
    public MediaPlan {
        items = List.copyOf(items);
        warnings = List.copyOf(warnings);
    }
}
