// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.story;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;
import org.oblivios.decoy.content.Persona;
import org.oblivios.decoy.content.Draws;
import org.oblivios.decoy.content.country.CountryPack;
import org.oblivios.decoy.content.data.DataSet;
import org.oblivios.decoy.content.lang.LanguagePack;
import org.oblivios.decoy.content.world.World;
import org.oblivios.decoy.content.world.WorldBuilder;
import org.oblivios.decoy.content.world.WorldPlace;

/** The one timeline behind every surface: where the owner lives and works, when the day starts, and since when the phone exists. */
public final class Timeline {
    private final List<Place> places;
    private final LocalTime wakeTime;
    private final LocalTime sleepTime;
    private final Instant phoneStart;
    private final Instant now;
    private final ZoneId zone;
    private final List<String> warnings;

    private Timeline(List<Place> places, LocalTime wake, LocalTime sleep, Instant phoneStart, Instant now, ZoneId zone,
            List<String> warnings) {
        this.places = List.copyOf(places);
        this.wakeTime = wake;
        this.sleepTime = sleep;
        this.phoneStart = phoneStart;
        this.now = now;
        this.zone = zone;
        this.warnings = List.copyOf(warnings);
    }

    /**
     * The timeline of a world built from the same draws. Throws {@link IllegalArgumentException} for a city or country
     * the world cannot be built for.
     */
    public static Timeline generate(Persona p, VolumeModel v, CountryPack c, Instant now, Draws s) {
        return generate(p, v, WorldBuilder.build(p, c, DataSet.of(c.code()), s), now, s);
    }

    public static Timeline generate(Persona p, VolumeModel v, World world, Instant now, Draws s) {
        RandomGenerator r = s.stream("timeline");
        List<String> warnings = new ArrayList<>();

        // shortcut: the views know home, work, cafes and shops; the year of the story takes the other places (plan Task 10)
        LanguagePack lang = LanguagePack.of(p.language());
        List<Place> places = new ArrayList<>();
        for (WorldPlace at : world.places()) {
            PlaceKind kind = at.kind() == PlaceKind.SUPERMARKET ? PlaceKind.SHOP : at.kind();
            String name = switch (kind) {
                case HOME -> lang.text("place.home");
                case WORK -> lang.text("place.work");
                case CAFE, SHOP -> at.name();
                default -> null;
            };
            if (name != null) {
                places.add(new Place(name, at.lat(), at.lon(), kind));
            }
        }

        LocalTime wake = LocalTime.of(6, 0).plusMinutes(r.nextInt(151)); // 06:00..08:30
        LocalTime sleep = LocalTime.of(22, 30).plusMinutes(r.nextInt(136)); // 22:30..00:45

        Instant first = v.months().get(0).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant candidate = first.minus(Duration.ofDays(r.nextInt(29)));
        Instant limit = now.minus(Duration.ofDays(180L + r.nextInt(91)));
        Instant phoneStart = candidate.isBefore(limit) ? candidate : limit;

        return new Timeline(places, wake, sleep, phoneStart, now, p.inputs().zone(), warnings);
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

    /** The time zone of the owner's story; day and hour of every event are read in it. */
    public ZoneId zone() {
        return zone;
    }

    public List<String> warnings() {
        return warnings;
    }

    /** Value equality over everything the timeline holds, so that two plans built from one seed compare equal. */
    @Override
    public boolean equals(Object o) {
        return o instanceof Timeline t && places.equals(t.places) && wakeTime.equals(t.wakeTime) && sleepTime.equals(t.sleepTime)
                && phoneStart.equals(t.phoneStart) && now.equals(t.now) && zone.equals(t.zone) && warnings.equals(t.warnings);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(places, wakeTime, sleepTime, phoneStart, now, zone, warnings);
    }
}
