// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.chronicle;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.Month;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.random.RandomGenerator;
import java.util.stream.Stream;
import org.oblivios.decoy.content.Draws;
import org.oblivios.decoy.content.Persona;
import org.oblivios.decoy.content.PersonaInputs;
import org.oblivios.decoy.content.SetupInputs;
import org.oblivios.decoy.content.country.CountryPack;
import org.oblivios.decoy.content.country.Packs;
import org.oblivios.decoy.content.data.DataSet;
import org.oblivios.decoy.content.data.HolidayData;
import org.oblivios.decoy.content.data.PlaceData;
import org.oblivios.decoy.content.data.SchoolHoliday;
import org.oblivios.decoy.content.origin.Origin;
import org.oblivios.decoy.content.persona.PersonaProfile.JobKind;
import org.oblivios.decoy.content.persona.Question;
import org.oblivios.decoy.content.story.PlaceKind;
import org.oblivios.decoy.content.story.VolumeModel;
import org.oblivios.decoy.content.surface.Trip;
import org.oblivios.decoy.content.world.World;
import org.oblivios.decoy.content.world.WorldBuilder;

/**
 * The chronicle: {@code frame} makes the year, the routine with its exceptions, and the life events and trips;
 * {@code complete} adds the small events and the traces.
 * How much leave and how many sick days a person has, and how the nights of a trip are clipped by its journeys, is
 * {@code [assumption]}.
 */
public final class ChronicleBuilder {
    private static final int MINUTES_PER_DAY = 24 * 60;
    private static final Duration AFTER_ARRIVAL = Duration.ofMinutes(45);
    private static final Duration BEFORE_LEAVING = Duration.ofMinutes(60);
    private static final Duration SHORTEST_NIGHT_AWAY = Duration.ofHours(3);
    private static final int SINGLE_LEAVE_DAYS = 5;
    private static final int LEAVE_WEEKS = 2;
    private static final int MOST_SICK_SPELLS = 2;
    private static final int LONGEST_SICK_SPELL = 3;

    private ChronicleBuilder() {}

    public static Chronicle frame(Persona p, World w, DataSet data, SetupInputs in, Draws d) {
        return frame(p, w, data, VolumeModel.fromCounts(in.counts(), d), in.now(), d);
    }

    /** As {@link #frame(Persona, World, DataSet, SetupInputs, Draws)}, from a volume that is already counted. */
    public static Chronicle frame(Persona p, World w, DataSet data, VolumeModel volume, Instant now, Draws d) {
        PersonaInputs inputs = p.inputs();
        ZoneId zone = inputs.zone();
        CountryPack pack = Packs.forDevice(inputs.countryCode(), zone, inputs.locale()).pack();
        HolidayData holidays = data.holidays();
        String region = PlaceData.require(pack.code(), inputs.city()).schoolRegion();
        boolean schoolChildren = p.childAges().stream().anyMatch(a -> a >= 4 && a < 18);
        boolean followsSchool = schoolChildren || p.job() == JobKind.PUPIL;
        Instant phoneStart = phoneStart(volume, now, d);
        if (followsSchool) {
            Instant first = holidays.firstDay().atStartOfDay(zone).toInstant();
            phoneStart = phoneStart.isBefore(first) ? first : phoneStart;
        }
        LocalDate from = LocalDate.ofInstant(phoneStart, zone);
        LocalDate to = LocalDate.ofInstant(now, zone);
        YearFrame year = YearFrame.of(pack, holidays, region, from.minusDays(1), to.plusDays(1), p.holidays());
        Routine.Drawn drawn = Routine.of(p, w, data.places(), d);

        Set<LocalDate> sick = sickDays(p, from, to, d);
        Set<LocalDate> leave = leaveDays(p, year, holidays.holidays(region), schoolChildren, from, to, d);

        List<Trips.Planned> trips = Trips.plan(p,
                new Trips.Frame(drawn.routine(), year, followsSchool, phoneStart, now, zone), pack.code(), data, d);
        for (Trips.Planned t : trips) {
            t.first().datesUntil(t.last().plusDays(1)).forEach(day -> {
                sick.remove(day);
                leave.add(day);
            });
            // a shift through the night before would end on the way there
            LocalDate before = t.first().minusDays(1);
            if (drawn.routine().on(before).stream().anyMatch(b -> b.work() && b.to() >= MINUTES_PER_DAY)) {
                leave.add(before);
            }
        }

        Events events = new Events(zone, phoneStart, now);
        Origin city = Origin.answer(Question.CITY.name());
        Origin self = Origin.world(w.self().id());
        if (followsSchool) {
            for (SchoolHoliday h : holidays.holidays(region)) {
                events.days(EventKind.SCHOOL_HOLIDAY, h.from(), h.to(), List.of(), city, Event.Layer.YEAR);
            }
        }
        Map<LocalDate, List<Block>> days = new HashMap<>();
        // the day before the phone's first one gives the sleep it wakes from
        for (LocalDate day = from.minusDays(1); !day.isAfter(to.plusDays(1)); day = day.plusDays(1)) {
            LocalDate of = day;
            days.put(day, onTrip(trips, day).map(t -> journeys(t, of, zone, self))
                    .orElseGet(() -> holding(p, w, drawn.routine(), year, of, leave, sick)));
        }
        for (LocalDate day = from.minusDays(1); !day.isAfter(to); day = day.plusDays(1)) {
            if (year.publicHoliday(day)) {
                events.days(EventKind.PUBLIC_HOLIDAY, day, day, List.of(), city, Event.Layer.YEAR);
            }
            // a day off is only a day off where the pattern had work
            boolean works = drawn.routine().on(day).stream().anyMatch(Block::work);
            if (works && !year.publicHoliday(day) && sick.contains(day)) {
                events.days(EventKind.SICK, day, day, List.of(), self, Event.Layer.ROUTINE);
            } else if (works && !year.publicHoliday(day) && leave.contains(day)) {
                events.days(EventKind.LEAVE, day, day, List.of(), self, Event.Layer.ROUTINE);
            }
            for (Block b : days.get(day)) {
                if (b.kind() == EventKind.TRAVEL_LEG) {
                    continue; // the journeys of a trip are its own events; here they only move the sleep around them
                }
                events.block(day, b);
                int travel = Routine.wayHomeAfterMidnight(days.get(day));
                if (b.work() && travel > 0) {
                    events.timed(EventKind.COMMUTE, day, b.to(), b.to() + travel, false, WorldBuilder.HOME, List.of(), b.origin());
                }
            }
            Optional<Trips.Planned> away = onTrip(trips, day);
            if (away.isEmpty() || away.get().last().equals(day)) {
                int[] sleep = drawn.routine().sleepAfter(days.get(day), days.get(day.plusDays(1)), drawn.own());
                events.timed(EventKind.SLEEP, day, sleep[0], sleep[1], false, WorldBuilder.HOME, List.of(), self);
            }
        }
        LifeEvents.birthdays(events, w, from, to);
        List<Trip> travelled = new ArrayList<>();
        for (Trips.Planned t : trips) {
            travelled.add(events.trip(t, drawn.routine(), self));
        }
        for (int i = 0; i < trips.size(); i++) {
            Instant at = Trips.bookingTime(trips.get(i), new Availability(events.sorted()), phoneStart, zone);
            ZoneId there = travelled.stream().filter(t -> !at.isBefore(t.from()) && at.isBefore(t.to())).map(Trip::zone)
                    .findFirst().orElse(zone);
            events.add(EventKind.BOOKING, LocalDate.ofInstant(at, there), at, at.plus(Trips.BOOKING), there, "", List.of(),
                    Origin.event(travelled.get(i).id()), Event.Layer.LIFE);
        }
        return new Chronicle(events.sorted(), drawn.routine(), travelled, phoneStart, now, zone);
    }

    /**
     * Layers 5 and 6 on a frame: the small events around the frame and the placed conversations, and then the traces of
     * every event. An event that already has traces, as a conversation's does, keeps them.
     */
    public static Chronicle complete(Chronicle frame, List<Event> conversationEvents, World w, DataSet data, Draws d) {
        Chronicle before = new Chronicle(sorted(Stream.concat(frame.events().stream(), conversationEvents.stream())),
                frame.routine(), frame.trips(), frame.phoneStart(), frame.now(), frame.zone());
        List<Event> small = SmallEvents.add(before, w, data.pack(), data.spreads(), d);
        Stream<Event> all = Stream.concat(before.events().stream(), small.stream())
                .map(e -> e.traces().isEmpty()
                        ? new Event(e.id(), e.kind(), e.start(), e.end(), e.zone(), e.placeId(), e.people(), e.layer(),
                                Traces.of(e, w.habits(), d.stream("trace." + e.id())), e.details(), e.origin())
                        : e);
        return new Chronicle(sorted(all), frame.routine(), frame.trips(), frame.phoneStart(), frame.now(), frame.zone());
    }

    private static List<Event> sorted(Stream<Event> events) {
        return events.sorted(Comparator.comparing(Event::start).thenComparing(Event::id)).toList();
    }

    private static Optional<Trips.Planned> onTrip(List<Trips.Planned> trips, LocalDate day) {
        return trips.stream().filter(t -> t.covers(day)).findFirst();
    }

    /** The blocks of a day of a trip: nothing of the routine, only when the persona leaves and is back. */
    private static List<Block> journeys(Trips.Planned t, LocalDate day, ZoneId zone, Origin origin) {
        List<Block> out = new ArrayList<>();
        if (t.first().equals(day)) {
            LocalTime leaves = LocalTime.ofInstant(t.out(), zone);
            out.add(new Block(EventKind.TRAVEL_LEG, leaves, leaves.plusMinutes(1), "", List.of(), origin));
        }
        if (t.last().equals(day)) {
            LocalTime back = LocalTime.ofInstant(t.home(), zone);
            out.add(new Block(EventKind.TRAVEL_LEG, back.minusMinutes(1), back, "", List.of(), origin));
        }
        return out;
    }

    /** The blocks of the pattern that hold on this day, with what holidays, leave and sickness take away. */
    private static List<Block> holding(Persona p, World w, Routine routine, YearFrame year, LocalDate day, Set<LocalDate> leave,
            Set<LocalDate> sick) {
        boolean holiday = year.publicHoliday(day);
        boolean school = year.schoolHoliday(day);
        boolean off = holiday || sick.contains(day) || leave.contains(day) || school && p.job() == JobKind.PUPIL;
        return routine.on(day).stream().filter(b -> switch (b.kind()) {
            case WORK, SCHOOL, COMMUTE -> !off;
            case BRING_CHILD, FETCH_CHILD -> !holiday && !(school && w.place(b.placeId())
                    .map(place -> place.kind() == PlaceKind.SCHOOL).orElse(false));
            case ACTIVITY -> !sick.contains(day);
            default -> true;
        }).toList();
    }

    private static Instant phoneStart(VolumeModel v, Instant now, Draws d) {
        RandomGenerator r = d.stream("chronicle.phoneStart");
        Instant first = v.months().get(0).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant candidate = first.minus(Duration.ofDays(r.nextInt(29)));
        Instant limit = now.minus(Duration.ofDays(180L + r.nextInt(91)));
        return candidate.isBefore(limit) ? candidate : limit;
    }

    private static Set<LocalDate> sickDays(Persona p, LocalDate from, LocalDate to, Draws d) {
        Set<LocalDate> out = new HashSet<>();
        for (int year = from.getYear(); year <= to.getYear(); year++) {
            RandomGenerator r = d.stream("chronicle.sick." + year);
            for (int spells = r.nextInt(MOST_SICK_SPELLS + 1); spells > 0; spells--) {
                LocalDate first = LocalDate.ofYearDay(year, 1 + r.nextInt(365));
                for (int i = r.nextInt(LONGEST_SICK_SPELL); i >= 0; i--) {
                    out.add(first.plusDays(i));
                }
            }
        }
        return p.work().days().isEmpty() ? new HashSet<>() : out;
    }

    /** Two weeks in the summer, with school children in their school holiday, and a few single days; employees only. */
    private static Set<LocalDate> leaveDays(Persona p, YearFrame frame, List<SchoolHoliday> school, boolean schoolChildren,
            LocalDate from, LocalDate to, Draws d) {
        if (p.job() != JobKind.OFFICE && p.job() != JobKind.SHIFT && p.job() != JobKind.SELF_EMPLOYED) {
            return new HashSet<>();
        }
        Set<LocalDate> out = new HashSet<>();
        for (int year = from.getYear(); year <= to.getYear(); year++) {
            RandomGenerator r = d.stream("chronicle.leave." + year);
            LocalDate summer = LocalDate.of(year, Month.JUNE, 1).with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY))
                    .plusWeeks(r.nextInt(13));
            if (schoolChildren) {
                int of = year;
                // the summer holiday is the long one that starts in July or August
                summer = school.stream()
                        .filter(h -> h.from().getYear() == of && (h.from().getMonth() == Month.JULY
                                || h.from().getMonth() == Month.AUGUST))
                        .findFirst().map(h -> h.from().plusWeeks(r.nextInt(4))).orElse(summer);
            }
            for (int i = 0; i < 7 * LEAVE_WEEKS; i++) {
                out.add(summer.plusDays(i));
            }
            int single = SINGLE_LEAVE_DAYS;
            for (int tries = 0; single > 0 && tries < 100; tries++) {
                LocalDate day = LocalDate.ofYearDay(year, 1 + r.nextInt(365));
                if (p.work().days().contains(day.getDayOfWeek()) && !frame.publicHoliday(day) && out.add(day)) {
                    single--;
                }
            }
        }
        return out;
    }

    /** Collects the events that start before now and end after the start of the phone. */
    static final class Events {
        private final List<Event> out = new ArrayList<>();
        private final Map<String, Integer> counts = new HashMap<>();
        private final ZoneId zone;
        private final Instant phoneStart;
        private final Instant now;

        Events(ZoneId zone, Instant phoneStart, Instant now) {
            this.zone = zone;
            this.phoneStart = phoneStart;
            this.now = now;
        }

        void block(LocalDate day, Block b) {
            timed(b.kind(), day, b.from(), b.to(), b.kind() == EventKind.ACTIVITY, b.placeId(), b.people(), b.origin());
        }

        /**
         * Minutes from the midnight of {@code day}. A local time that a change of the clock skips moves forward. With
         * {@code lasts} the event keeps its length; else it ends at its local end.
         */
        void timed(EventKind kind, LocalDate day, int from, int to, boolean lasts, String placeId, List<String> people,
                Origin origin) {
            Instant start = instant(day, from);
            Instant end = lasts ? start.plus(Duration.ofMinutes(to - from)) : instant(day, to);
            if (!end.isAfter(start)) {
                end = start.plus(Duration.ofMinutes(to - from));
            }
            add(kind, day, start, end, zone, placeId, people, origin, Event.Layer.ROUTINE);
        }

        void days(EventKind kind, LocalDate first, LocalDate last, List<String> people, Origin origin, Event.Layer layer) {
            add(kind, first, first.atStartOfDay(zone).toInstant(), last.plusDays(1).atStartOfDay(zone).toInstant(), zone, "",
                    people, origin, layer);
        }

        /**
         * A trip, its two journeys and its nights, all in the destination's zone; the nights follow the persona's own
         * times for a free day, by the clock there. The trip was planned inside the time of the phone.
         */
        Trip trip(Trips.Planned t, Routine routine, Origin self) {
            String place = t.places().get(0).id();
            Origin answered = Origin.answer(Question.TRIPS.name());
            String id = add(EventKind.TRIP, t.first(), t.out(), t.home(), t.zone(), place, List.of(), answered, Event.Layer.LIFE);
            Origin of = Origin.event(id);
            add(EventKind.TRAVEL_LEG, t.first(), t.out(), t.arrive(), t.zone(), place, List.of(), of, Event.Layer.LIFE);
            add(EventKind.TRAVEL_LEG, t.last(), t.back(), t.home(), t.zone(), WorldBuilder.HOME, List.of(), of,
                    Event.Layer.LIFE);
            LocalDate last = LocalDate.ofInstant(t.back(), t.zone());
            for (LocalDate night = LocalDate.ofInstant(t.arrive(), t.zone()).minusDays(1); !night.isAfter(last);
                    night = night.plusDays(1)) {
                boolean afterMidnight = routine.sleepFree().isBefore(LocalTime.NOON);
                Instant bed = (afterMidnight ? night.plusDays(1) : night).atTime(routine.sleepFree()).atZone(t.zone())
                        .toInstant();
                Instant wake = night.plusDays(1).atTime(routine.wakeFree()).atZone(t.zone()).toInstant();
                bed = later(bed, t.arrive().plus(AFTER_ARRIVAL));
                wake = wake.isBefore(t.back().minus(BEFORE_LEAVING)) ? wake : t.back().minus(BEFORE_LEAVING);
                if (!wake.isBefore(bed.plus(SHORTEST_NIGHT_AWAY))) {
                    add(EventKind.SLEEP, night, bed, wake, t.zone(), place, List.of(), self, Event.Layer.LIFE);
                }
            }
            return new Trip(id, t.places(), t.mode(), t.out(), t.home(), t.zone(), answered);
        }

        private static Instant later(Instant a, Instant b) {
            return a.isAfter(b) ? a : b;
        }

        private Instant instant(LocalDate day, int minutes) {
            return day.atStartOfDay().plusMinutes(minutes).atZone(zone).toInstant();
        }

        /** The id of the event, or {@code null} for one that is outside the time of the phone. */
        String add(EventKind kind, LocalDate day, Instant start, Instant end, ZoneId in, String placeId, List<String> people,
                Origin origin, Event.Layer layer) {
            if (!start.isBefore(now) || !end.isAfter(phoneStart)) {
                return null;
            }
            String name = "event." + kind.name().toLowerCase(Locale.ROOT) + "." + day;
            int n = counts.merge(name, 1, Integer::sum);
            out.add(new Event(name + "." + n, kind, start, end, in, placeId, people, layer, Set.of(), Map.of(), origin));
            return name + "." + n;
        }

        List<Event> sorted() {
            return ChronicleBuilder.sorted(out.stream());
        }
    }
}
