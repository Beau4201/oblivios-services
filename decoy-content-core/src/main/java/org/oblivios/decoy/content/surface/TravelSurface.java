// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.surface;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.random.RandomGenerator;
import org.oblivios.decoy.content.Seed;
import org.oblivios.decoy.content.country.CountryPack;
import org.oblivios.decoy.content.story.Place;
import org.oblivios.decoy.content.story.PlaceKind;
import org.oblivios.decoy.content.story.Timeline;

/** Trips inside the story, one per slice of the phone's life so that none overlaps. */
public final class TravelSurface {
    private record City(String name, double lat, double lon) {}

    // shortcut: approximate city centres written by hand; the setup's place data (GeoNames) can replace them.
    private static final List<City> ABROAD = List.of(
            new City("Paris", 48.86, 2.35), new City("Berlin", 52.52, 13.40), new City("Barcelona", 41.39, 2.17),
            new City("Lisbon", 38.72, -9.14), new City("Rome", 41.90, 12.50), new City("Vienna", 48.21, 16.37),
            new City("Prague", 50.08, 14.44), new City("Copenhagen", 55.68, 12.57), new City("London", 51.51, -0.13),
            new City("Athens", 37.98, 23.73), new City("Madrid", 40.42, -3.70), new City("Dublin", 53.35, -6.26));
    private static final List<City> DOMESTIC_NL = List.of(
            new City("Maastricht", 50.85, 5.69), new City("Groningen", 53.22, 6.57), new City("Leiden", 52.16, 4.49),
            new City("Zwolle", 52.51, 6.09), new City("Haarlem", 52.38, 4.64), new City("Arnhem", 51.98, 5.91));

    private TravelSurface() {}

    public static TravelPlan generate(Timeline t, CountryPack c, Seed s) {
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
            boolean flight = !"NL".equals(c.code()) || r.nextInt(100) < 60;
            List<City> pool = flight ? ABROAD : DOMESTIC_NL;
            City city = pool.get(r.nextInt(pool.size()));
            List<Place> places = new ArrayList<>();
            int count = 1 + r.nextInt(3);
            for (int k = 0; k < count; k++) {
                String label = k == 0 ? "Hotel " + city.name() : k == 1 ? city.name() + " centre" : city.name() + " station";
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
