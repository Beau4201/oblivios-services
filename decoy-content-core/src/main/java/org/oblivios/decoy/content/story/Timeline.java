// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.story;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.oblivios.decoy.content.Persona;
import org.oblivios.decoy.content.PersonaInputs;
import org.oblivios.decoy.content.Draws;
import org.oblivios.decoy.content.chronicle.Chronicle;
import org.oblivios.decoy.content.chronicle.ChronicleBuilder;
import org.oblivios.decoy.content.country.CountryPack;
import org.oblivios.decoy.content.country.Packs;
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
        PersonaInputs in = p.inputs();
        DataSet data = DataSet.of(Packs.forDevice(in.countryCode(), in.zone(), in.locale()).pack().code());
        return of(ChronicleBuilder.frame(p, world, data, v, now, s), world, LanguagePack.of(p.language()));
    }

    /** What the views of today read of a chronicle: the places they know and the times of a working day. */
    public static Timeline of(Chronicle c, World world, LanguagePack lang) {
        // shortcut: the views know home, work, cafes and shops, until they read the chronicle (plan Task 22)
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
        return new Timeline(places, c.routine().wakeWorkday(), c.routine().sleepWorkday(), c.phoneStart(), c.now(), c.zone(),
                List.of());
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
