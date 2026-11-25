// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.story;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;
import org.oblivios.decoy.content.Persona;
import org.oblivios.decoy.content.Seed;
import org.oblivios.decoy.content.country.CountryPack;
import org.oblivios.decoy.content.country.Sender;
import org.oblivios.decoy.content.country.SenderKind;

/** The one timeline behind every surface: where the owner lives and works, when the day starts, and since when the phone exists. */
public final class Timeline {
    private final List<Place> places;
    private final LocalTime wakeTime;
    private final LocalTime sleepTime;
    private final Instant phoneStart;
    private final Instant now;
    private final List<String> warnings;

    private Timeline(List<Place> places, LocalTime wake, LocalTime sleep, Instant phoneStart, Instant now, List<String> warnings) {
        this.places = List.copyOf(places);
        this.wakeTime = wake;
        this.sleepTime = sleep;
        this.phoneStart = phoneStart;
        this.now = now;
        this.warnings = List.copyOf(warnings);
    }

    /** Throws {@link IllegalArgumentException} for a country that has no place data (see {@code PlaceBook}). */
    public static Timeline generate(Persona p, VolumeModel v, CountryPack c, Instant now, Seed s) {
        RandomGenerator r = s.stream("timeline");
        List<String> warnings = new ArrayList<>();

        double[] centre = PlaceBook.city(p.inputs().countryCode(), p.inputs().city()).orElse(null);
        if (centre == null) {
            centre = PlaceBook.capital(p.inputs().countryCode());
            warnings.add("city '" + p.inputs().city() + "' has no place data: the capital is used");
        }

        List<Place> places = new ArrayList<>();
        double[] home = around(centre, 0.3, 4.0, r);
        places.add(new Place("Home", home[0], home[1], PlaceKind.HOME));
        double[] work = around(home, 3.0, 15.0, r);
        places.add(new Place("Work", work[0], work[1], PlaceKind.WORK));
        for (int i = 0; i < 2; i++) {
            double[] cafe = around(home, 0.3, 3.0, r);
            places.add(new Place("Cafe " + c.familyName(r), cafe[0], cafe[1], PlaceKind.CAFE));
        }
        List<Sender> shops = c.senders(SenderKind.SHOP);
        for (int i = 0; i < 3; i++) {
            double[] shop = around(home, 0.3, 4.0, r);
            String name = shops.isEmpty() ? "Supermarket" : shops.get(r.nextInt(shops.size())).name();
            places.add(new Place(name, shop[0], shop[1], PlaceKind.SHOP));
        }

        LocalTime wake = LocalTime.of(6, 0).plusMinutes(r.nextInt(151)); // 06:00..08:30
        LocalTime sleep = LocalTime.of(22, 30).plusMinutes(r.nextInt(136)); // 22:30..00:45

        Instant first = v.months().get(0).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant candidate = first.minus(Duration.ofDays(r.nextInt(29)));
        Instant limit = now.minus(Duration.ofDays(180L + r.nextInt(91)));
        Instant phoneStart = candidate.isBefore(limit) ? candidate : limit;

        return new Timeline(places, wake, sleep, phoneStart, now, warnings);
    }

    /** A point between {@code minKm} and {@code maxKm} from {@code from}, uniform over the ring's area. */
    private static double[] around(double[] from, double minKm, double maxKm, RandomGenerator r) {
        double d = Math.sqrt(minKm * minKm + r.nextDouble() * (maxKm * maxKm - minKm * minKm));
        double angle = r.nextDouble() * 2 * Math.PI;
        double lat = from[0] + d * Math.cos(angle) / 111.32;
        double lon = from[1] + d * Math.sin(angle) / (111.32 * Math.cos(Math.toRadians(from[0])));
        return new double[] {lat, lon};
    }

    public List<Place> places() {
        return places;
    }

    public LocalTime wakeTime() {
        return wakeTime;
    }

    public LocalTime sleepTime() {
        return sleepTime;
    }

    public Instant phoneStart() {
        return phoneStart;
    }

    /** The moment the story ends: no event of any surface lies after it. */
    public Instant now() {
        return now;
    }

    public List<String> warnings() {
        return warnings;
    }
}
