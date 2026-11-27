// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.chronicle;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.random.RandomGenerator;
import org.oblivios.decoy.content.Draws;
import org.oblivios.decoy.content.Persona;
import org.oblivios.decoy.content.check.Checks;
import org.oblivios.decoy.content.data.CityRef;
import org.oblivios.decoy.content.data.DataSet;
import org.oblivios.decoy.content.data.PlaceData;
import org.oblivios.decoy.content.origin.Origin;
import org.oblivios.decoy.content.persona.PersonaProfile.TravelMode;
import org.oblivios.decoy.content.persona.Question;
import org.oblivios.decoy.content.persona.TripAnswer;
import org.oblivios.decoy.content.story.PlaceKind;
import org.oblivios.decoy.content.world.WorldPlace;

/**
 * When and where the persona travels: the past year as answered, earlier years in agreement with it, and no trip
 * without an answer. How often a trip of one year was also made before, how fast each means of travel is, when people
 * leave, come home and book are {@code [assumption]}.
 */
final class Trips {
    private static final int MADE_BEFORE_PERCENT = 70;
    private static final int DAYS_APART = 2;
    /** A trip in the home country goes at least this far, where the country has such a place. */
    private static final double NOT_NEAR_METRES = 40_000;
    /** The longest journey one way, and the longest on a trip of one day, in hours. */
    private static final int LONGEST_JOURNEY = 36;
    private static final int LONGEST_DAY_JOURNEY = 5;
    private static final Duration SHORTEST_STAY = Duration.ofHours(3);
    static final Duration BOOKING = Duration.ofMinutes(10);

    private Trips() {}

    /**
     * A trip before it is in the chronicle: {@code first} and {@code last} are the dates at home, {@code out} to
     * {@code arrive} the way there and {@code back} to {@code home} the way back.
     */
    record Planned(LocalDate first, LocalDate last, TravelMode mode, ZoneId zone, List<WorldPlace> places, Instant out,
            Instant arrive, Instant back, Instant home, LocalDate booked, int bookedMinute) {

        boolean covers(LocalDate day) {
            return !day.isBefore(first) && !day.isAfter(last);
        }
    }

    private record Wanted(String key, YearMonth month, int days, String country, TravelMode mode, boolean answered) {}

    /** What every placement reads. A person who follows the school goes on a drawn trip in its holidays only. */
    record Frame(Routine routine, YearFrame year, boolean followsSchool, Instant phoneStart, Instant now, ZoneId zone) {}

    /**
     * The trips of the story, the earliest first. A trip that would start before the phone is not part of it. An
     * answered trip that cannot be made throws {@link IllegalArgumentException} whose message starts with
     * {@code trips: }; an earlier one that finds no place is left out.
     */
    static List<Planned> plan(Persona p, Frame f, String countryCode, DataSet data, Draws d) {
        LocalDate earliest = LocalDate.ofInstant(f.phoneStart(), f.zone()).plusDays(2);
        List<Planned> out = new ArrayList<>();
        for (Wanted w : wanted(p, earliest, d)) {
            RandomGenerator r = d.stream("chronicle.trip." + w.key());
            LocalDate first = firstDay(w, f, out, earliest, r);
            if (first == null) {
                if (w.answered() && !w.month().atDay(1).isBefore(earliest)) {
                    throw new IllegalArgumentException("trips: no room for " + w.days() + " days in " + w.month());
                }
                continue;
            }
            Planned trip = journey(p, w, f, countryCode, data, first, earliest, r);
            if (trip != null) {
                out.add(trip);
            }
        }
        out.sort(Comparator.comparing(Planned::first));
        return out;
    }

    /** The answers first, so that they take their days before the earlier trips do. */
    private static List<Wanted> wanted(Persona p, LocalDate earliest, Draws d) {
        List<Wanted> out = new ArrayList<>();
        List<TripAnswer> answers = p.trips();
        for (int i = 0; i < answers.size(); i++) {
            TripAnswer a = answers.get(i);
            out.add(new Wanted("answer." + i, a.month(), a.days(), a.country().toUpperCase(Locale.ROOT), a.mode(), true));
        }
        YearMonth pastYear = YearMonth.from(p.today()).minusMonths(12);
        for (int i = 0; i < answers.size(); i++) {
            TripAnswer a = answers.get(i);
            for (YearMonth m = a.month().minusYears(1); !m.atEndOfMonth().isBefore(earliest); m = m.minusYears(1)) {
                RandomGenerator r = d.stream("chronicle.tripBefore." + i + "." + m);
                if (m.isBefore(pastYear) && r.nextInt(100) < MADE_BEFORE_PERCENT) {
                    int days = Math.max(1, a.days() + r.nextInt(2 * DAYS_APART + 1) - DAYS_APART);
                    out.add(new Wanted("earlier." + i + "." + m, m, days, a.country().toUpperCase(Locale.ROOT), a.mode(),
                            false));
                }
            }
        }
        return out;
    }

    /** A day to leave on that costs the fewest school days, then about the fewest days of leave; {@code null} for none. */
    private static LocalDate firstDay(Wanted w, Frame f, List<Planned> taken, LocalDate earliest, RandomGenerator r) {
        LocalDate latest = LocalDate.ofInstant(f.now(), f.zone()).minusDays(1);
        List<LocalDate> open = new ArrayList<>(w.month().atDay(1).datesUntil(w.month().atEndOfMonth().plusDays(1))
                .filter(day -> fits(day, w.days(), taken, earliest, latest)).toList());
        // a trip of this month that is not over yet started in the month before
        LocalDate justOver = latest.minusDays(w.days() - 1L);
        if (open.isEmpty() && w.answered() && w.month().equals(YearMonth.from(latest))
                && fits(justOver, w.days(), taken, earliest, latest)) {
            open.add(justOver);
        }
        if (f.followsSchool()) {
            long least = open.stream().mapToLong(day -> schoolDays(day, w.days(), f.year())).min().orElse(0);
            if (least > 0 && !w.answered()) {
                return null;
            }
            open.removeIf(day -> schoolDays(day, w.days(), f.year()) > least);
        }
        long fewest = open.stream().mapToLong(day -> workDays(day, w.days(), f)).min().orElse(0);
        open.removeIf(day -> workDays(day, w.days(), f) > fewest + 1);
        return open.isEmpty() ? null : open.get(r.nextInt(open.size()));
    }

    /** Between two trips lies a day at home. */
    private static boolean fits(LocalDate first, int days, List<Planned> taken, LocalDate earliest, LocalDate latest) {
        LocalDate last = first.plusDays(days - 1L);
        return !first.isBefore(earliest) && !last.isAfter(latest) && taken.stream()
                .allMatch(t -> first.isAfter(t.last().plusDays(1)) || last.isBefore(t.first().minusDays(1)));
    }

    private static long schoolDays(LocalDate first, int days, YearFrame year) {
        return first.datesUntil(first.plusDays(days)).filter(day -> day.getDayOfWeek().getValue() < DayOfWeek.SATURDAY.getValue()
                && !year.publicHoliday(day) && !year.schoolHoliday(day)).count();
    }

    private static long workDays(LocalDate first, int days, Frame f) {
        return first.datesUntil(first.plusDays(days))
                .filter(day -> !f.year().publicHoliday(day) && f.routine().on(day).stream().anyMatch(Block::work)).count();
    }

    private static Planned journey(Persona p, Wanted w, Frame f, String countryCode, DataSet data, LocalDate first,
            LocalDate earliest, RandomGenerator r) {
        CityRef home = PlaceData.require(countryCode, p.inputs().city());
        boolean domestic = w.country().equals(countryCode);
        record To(String name, double lat, double lon, ZoneId zone, String municipality, Duration way) {}
        List<To> all = new ArrayList<>();
        if (domestic) {
            for (CityRef c : data.places().municipalities()) {
                if (!c.municipality().equals(home.municipality())) {
                    all.add(new To(c.name(), c.lat(), c.lon(), f.zone(), c.municipality(), way(w.mode(), home, c.lat(), c.lon())));
                }
            }
            List<To> far = all.stream()
                    .filter(t -> Checks.metres(home.lat(), home.lon(), t.lat(), t.lon()) >= NOT_NEAR_METRES).toList();
            all = far.isEmpty() ? all : far;
        } else {
            for (PlaceData.Destination c : data.abroad(w.country())) {
                all.add(new To(c.name(), c.lat(), c.lon(), c.zone(), "", way(w.mode(), home, c.lat(), c.lon())));
            }
            if (all.isEmpty()) {
                throw new IllegalArgumentException("trips: no destination data for country " + w.country());
            }
        }
        int longest = w.days() == 1 ? LONGEST_DAY_JOURNEY : Math.min(LONGEST_JOURNEY, 12 * (w.days() - 1) + 4);
        List<To> reachable = all.stream().filter(t -> t.way().compareTo(Duration.ofHours(longest)) <= 0).toList();
        if (reachable.isEmpty()) {
            if (w.answered()) {
                throw new IllegalArgumentException("trips: " + w.days() + " days do not hold the journey to " + w.country()
                        + " by " + w.mode());
            }
            return null;
        }
        To to = reachable.get(r.nextInt(reachable.size()));
        LocalDate last = first.plusDays(w.days() - 1L);
        boolean oneDay = w.days() == 1;
        Instant out = at(first, 6 * 60 + r.nextInt(oneDay ? 91 : 301), f.zone());
        Instant back = at(last, (oneDay ? 20 : 16) * 60 + r.nextInt(oneDay ? 121 : 361), f.zone()).minus(to.way());
        Instant homeAt = back.plus(to.way());
        if (back.isBefore(out.plus(to.way()).plus(SHORTEST_STAY))) {
            out = at(first, 6 * 60, f.zone());
            homeAt = at(last, 22 * 60, f.zone());
            back = homeAt.minus(to.way());
        }
        List<WorldPlace> places = new ArrayList<>();
        for (int k = r.nextInt(3); k >= 0; k--) {
            places.add(new WorldPlace("trip." + w.key() + "." + places.size(), PlaceKind.TRAVEL, to.name(),
                    to.lat() + (r.nextDouble() - 0.5) * 0.04, to.lon() + (r.nextDouble() - 0.5) * 0.06, to.municipality(),
                    Origin.answer(Question.TRIPS.name())));
        }
        LocalDate booked = first.minusDays(14 + r.nextInt(77));
        booked = booked.isBefore(earliest.minusDays(1)) ? earliest.minusDays(1) : booked;
        return new Planned(first, last, w.mode(), to.zone(), places, out, out.plus(to.way()), back, homeAt, booked,
                19 * 60 + r.nextInt(181));
    }

    private static Instant at(LocalDate day, int minute, ZoneId zone) {
        return day.atTime(LocalTime.ofSecondOfDay(minute * 60L)).atZone(zone).toInstant();
    }

    /** One way: the straight line, longer over road and rail, at a usual speed, with the time spent getting under way. */
    private static Duration way(TravelMode mode, CityRef from, double lat, double lon) {
        double km = Checks.metres(from.lat(), from.lon(), lat, lon) / 1000;
        double hours = switch (mode) {
            case CAR -> km * 1.25 / 80;
            case BUS -> km * 1.25 / 65;
            case TRAIN -> km * 1.25 / 110 + 0.5;
            case PLANE -> km / 750 + 3;
            case BOAT -> km / 35 + 1;
        };
        return Duration.ofMinutes(Math.round(hours * 60));
    }

    /** A free moment to book in, from the drawn evening on, else before it; after the start of the phone. */
    static Instant bookingTime(Planned t, Availability busy, Instant phoneStart, ZoneId zone) {
        Instant drawn = at(t.booked(), t.bookedMinute(), zone);
        Duration step = Duration.ofMinutes(30);
        for (Instant at = drawn; at.plus(BOOKING).isBefore(t.out()); at = at.plus(step)) {
            if (busy.free(at, at.plus(BOOKING))) {
                return at;
            }
        }
        for (Instant at = drawn.minus(step); at.isAfter(phoneStart); at = at.minus(step)) {
            if (busy.free(at, at.plus(BOOKING))) {
                return at;
            }
        }
        throw new IllegalStateException("no free moment to book the trip of " + t.first());
    }
}
