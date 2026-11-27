// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.chronicle;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
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
import java.util.Set;
import java.util.random.RandomGenerator;
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
import org.oblivios.decoy.content.world.World;
import org.oblivios.decoy.content.world.WorldBuilder;

/**
 * The first two layers of the chronicle: the year and the routine with its exceptions. How much leave and how many
 * sick days a person has is {@code [assumption]}.
 */
public final class ChronicleBuilder {
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

        Events events = new Events(zone, phoneStart, now);
        Origin city = Origin.answer(Question.CITY.name());
        Origin self = Origin.world(w.self().id());
        if (followsSchool) {
            for (SchoolHoliday h : holidays.holidays(region)) {
                events.days(EventKind.SCHOOL_HOLIDAY, h.from(), h.to(), city, Event.Layer.YEAR);
            }
        }
        Map<LocalDate, List<Block>> days = new HashMap<>();
        // the day before the phone's first one gives the sleep it wakes from
        for (LocalDate day = from.minusDays(1); !day.isAfter(to.plusDays(1)); day = day.plusDays(1)) {
            days.put(day, holding(p, w, drawn.routine(), year, day, leave, sick));
        }
        for (LocalDate day = from.minusDays(1); !day.isAfter(to); day = day.plusDays(1)) {
            if (year.publicHoliday(day)) {
                events.days(EventKind.PUBLIC_HOLIDAY, day, day, city, Event.Layer.YEAR);
            }
            // a day off is only a day off where the pattern had work
            boolean works = drawn.routine().on(day).stream().anyMatch(Block::work);
            if (works && !year.publicHoliday(day) && sick.contains(day)) {
                events.days(EventKind.SICK, day, day, self, Event.Layer.ROUTINE);
            } else if (works && !year.publicHoliday(day) && leave.contains(day)) {
                events.days(EventKind.LEAVE, day, day, self, Event.Layer.ROUTINE);
            }
            for (Block b : days.get(day)) {
                events.block(day, b);
                int travel = Routine.wayHomeAfterMidnight(days.get(day));
                if (b.work() && travel > 0) {
                    events.timed(EventKind.COMMUTE, day, b.to(), b.to() + travel, false, WorldBuilder.HOME, List.of(), b.origin());
                }
            }
            int[] sleep = drawn.routine().sleepAfter(days.get(day), days.get(day.plusDays(1)), drawn.own());
            events.timed(EventKind.SLEEP, day, sleep[0], sleep[1], false, WorldBuilder.HOME, List.of(), self);
        }
        return new Chronicle(events.sorted(), drawn.routine(), phoneStart, now, zone);
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
        return p.work().days().isEmpty() ? Set.of() : out;
    }

    /** Two weeks in the summer, with school children in their school holiday, and a few single days; employees only. */
    private static Set<LocalDate> leaveDays(Persona p, YearFrame frame, List<SchoolHoliday> school, boolean schoolChildren,
            LocalDate from, LocalDate to, Draws d) {
        if (p.job() != JobKind.OFFICE && p.job() != JobKind.SHIFT && p.job() != JobKind.SELF_EMPLOYED) {
            return Set.of();
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
    private static final class Events {
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
            add(kind, day, start, end, placeId, people, origin, Event.Layer.ROUTINE);
        }

        void days(EventKind kind, LocalDate first, LocalDate last, Origin origin, Event.Layer layer) {
            add(kind, first, first.atStartOfDay(zone).toInstant(), last.plusDays(1).atStartOfDay(zone).toInstant(), "", List.of(),
                    origin, layer);
        }

        private Instant instant(LocalDate day, int minutes) {
            return day.atStartOfDay().plusMinutes(minutes).atZone(zone).toInstant();
        }

        private void add(EventKind kind, LocalDate day, Instant start, Instant end, String placeId, List<String> people,
                Origin origin, Event.Layer layer) {
            if (!start.isBefore(now) || !end.isAfter(phoneStart)) {
                return;
            }
            String name = "event." + kind.name().toLowerCase(Locale.ROOT) + "." + day;
            int n = counts.merge(name, 1, Integer::sum);
            out.add(new Event(name + "." + n, kind, start, end, zone, placeId, people, layer, Set.of(), Map.of(), origin));
        }

        List<Event> sorted() {
            return out.stream().sorted(Comparator.comparing(Event::start).thenComparing(Event::id)).toList();
        }
    }
}
