// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.review;

import java.util.Map;
import java.util.Set;

/**
 * What the owner changed in the review: pinned values and redraw counts by item, and the items taken out. The planner
 * refuses edits until it applies them.
 */
public record ReviewEdits(Map<String, String> pins, Map<String, Integer> redraws, Set<String> removed) {
    public ReviewEdits {
        pins = Map.copyOf(pins);
        redraws = Map.copyOf(redraws);
        removed = Set.copyOf(removed);
    }

    public static ReviewEdits none() {
        return new ReviewEdits(Map.of(), Map.of(), Set.of());
    }

    public boolean isEmpty() {
        return pins.isEmpty() && redraws.isEmpty() && removed.isEmpty();
    }
}
