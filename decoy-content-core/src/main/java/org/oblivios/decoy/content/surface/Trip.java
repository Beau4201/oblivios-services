// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.surface;

import java.time.Instant;
import java.util.List;
import org.oblivios.decoy.content.story.Place;

/** A trip with its dates, so photos and calendar entries of those days can sit at the trip's places. */
public record Trip(String id, List<Place> places, boolean flight, Instant from, Instant to) {}
