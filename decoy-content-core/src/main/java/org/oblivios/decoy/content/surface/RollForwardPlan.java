// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.surface;

import java.util.List;

/** The events to add, oldest first. */
public record RollForwardPlan(List<RollForwardEvent> events) {
    public RollForwardPlan {
        events = List.copyOf(events);
    }
}
