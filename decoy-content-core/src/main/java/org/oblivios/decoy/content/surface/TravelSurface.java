// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.surface;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.random.RandomGenerator;
import org.oblivios.decoy.content.Draws;
import org.oblivios.decoy.content.country.City;
import org.oblivios.decoy.content.country.CountryPack;
import org.oblivios.decoy.content.lang.LanguagePack;
import org.oblivios.decoy.content.story.Place;
import org.oblivios.decoy.content.story.PlaceKind;
import org.oblivios.decoy.content.story.Timeline;

/** Trips inside the story, one per slice of the phone's life so that none overlaps. */
public final class TravelSurface {
    private TravelSurface() {}

    /** {@code lang} is the persona's language, for the labels of the places of a trip. */
    public static TravelPlan generate(Timeline t, CountryPack c, LanguagePack lang, Draws s) {
        RandomGenerator r = s.stream("travel");
        ZoneId zone = t.zone();
        LocalDate startDate = LocalDate.ofInstant(t.phoneStart(), zone).plusDays(1);
        long days = Math.max(1, Duration.between(t.phoneStart(), t.now()).toDays() - 2);
        int n = (int) Math.max(2, Math.min(10, days / 90));
        long segment = days / n;
        List<Trip> trips = new ArrayList<>();
        List<String> boardingPasses = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            int length = 2 + r.nextInt(13); // 2..14 days
            long offset = r.nextLong(Math.max(1, segment - length - 1));
            LocalDate from = startDate.plusDays(i * segment + offset);
            Instant fromAt = from.atTime(9, 0).atZone(zone).toInstant();
            Instant toAt = from.plusDays(length).atTime(18, 0).atZone(zone).toInstant();
            boolean flight = c.domesticTrips().isEmpty() || r.nextInt(100) < 60;
            List<City> pool = flight ? c.abroadTrips() : c.domesticTrips();
            City city = pool.get(r.nextInt(pool.size()));
            List<Place> places = new ArrayList<>();
            int count = 1 + r.nextInt(3);
            for (int k = 0; k < count; k++) {
                String label = lang.format(k == 0 ? "travel.hotel" : k == 1 ? "travel.centre" : "travel.station", Map.of("place", city.name()));
                places.add(new Place(label, city.lat() + (r.nextDouble() - 0.5) * 0.04, city.lon() + (r.nextDouble() - 0.5) * 0.06, PlaceKind.TRAVEL));
            }
            String id = "trip" + i;
            trips.add(new Trip(id, places, flight, fromAt, toAt));
            if (flight && r.nextInt(100) < 70) {
                boardingPasses.add(id);
            }
        }
        trips.sort(Comparator.comparing(Trip::from));
        return new TravelPlan(trips, boardingPasses);
    }
}
