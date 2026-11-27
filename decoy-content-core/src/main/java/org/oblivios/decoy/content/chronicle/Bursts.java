// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.chronicle;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.TreeSet;
import java.util.random.RandomGenerator;
import org.oblivios.decoy.content.world.Rhythm;

/**
 * Instants as a person makes them: in groups seconds to minutes apart with long gaps between the groups, the groups
 * by the persona's own hours and only in free time. Every view that needs times takes them from here. How long a group
 * goes on and how close its instants lie is {@code [assumption]}.
 */
public final class Bursts {
    private static final double GOES_ON = 0.65;
    private static final long SHORTEST_GAP_MILLIS = 8_000;
    private static final double MEAN_GAP_MILLIS = 100_000;
    private static final int TRIES_PER_INSTANT = 200;
    private static final int HOUR_MILLIS = 3_600_000;

    private Bursts() {}

    /** Up to {@code n} instants of {@code day} in {@code zone}, in order; fewer only where the day has no free time. */
    public static List<Instant> times(int n, LocalDate day, ZoneId zone, Rhythm rhythm, Availability availability,
            RandomGenerator r) {
        TreeSet<Instant> out = new TreeSet<>();
        Instant start = day.atStartOfDay(zone).toInstant();
        Instant end = day.plusDays(1).atStartOfDay(zone).toInstant();
        Instant last = null;
        for (int tries = TRIES_PER_INSTANT * n; out.size() < n && tries > 0; tries--) {
            Instant at = last != null && r.nextDouble() < GOES_ON
                    ? last.plusMillis(SHORTEST_GAP_MILLIS + (long) (-Math.log(1 - r.nextDouble()) * MEAN_GAP_MILLIS))
                    : day.atTime(hour(rhythm, r), 0).atZone(zone).toInstant().plusMillis(r.nextInt(HOUR_MILLIS));
            boolean fits = !at.isBefore(start) && at.isBefore(end) && availability.free(at) && out.add(at);
            last = fits ? at : null;
        }
        return List.copyOf(out);
    }

    private static int hour(Rhythm rhythm, RandomGenerator r) {
        double roll = r.nextDouble() * rhythm.hourWeights().stream().mapToDouble(Double::doubleValue).sum();
        int h = 0;
        while (h < 23 && roll >= rhythm.hourWeights().get(h)) {
            roll -= rhythm.hourWeights().get(h);
            h++;
        }
        return h;
    }
}
