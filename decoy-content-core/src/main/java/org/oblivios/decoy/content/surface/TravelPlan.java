// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.surface;

import java.util.List;

/** {@code boardingPassTrips} holds the ids of flight trips for which a boarding pass screenshot exists. */
public record TravelPlan(List<Trip> trips, List<String> boardingPassTrips) {}
