// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.world;

import java.util.List;

/** How much of a day's calls and messages falls in each of the 24 hours, from midnight; the weights add up to 1. */
public record Rhythm(List<Double> hourWeights) {
    public Rhythm {
        hourWeights = List.copyOf(hourWeights);
    }
}
