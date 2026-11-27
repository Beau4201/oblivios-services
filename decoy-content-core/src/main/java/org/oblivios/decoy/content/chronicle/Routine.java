// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.chronicle;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.random.RandomGenerator;
import org.oblivios.decoy.content.Draws;
import org.oblivios.decoy.content.Persona;
import org.oblivios.decoy.content.data.PlaceData;
import org.oblivios.decoy.content.data.Venue;
import org.oblivios.decoy.content.origin.Origin;
import org.oblivios.decoy.content.persona.ActivityAnswer;
import org.oblivios.decoy.content.persona.ChildAnswer;
import org.oblivios.decoy.content.persona.ChildAnswer.Custody;
import org.oblivios.decoy.content.persona.PersonaProfile.JobKind;
import org.oblivios.decoy.content.persona.PersonaProfile.Transport;
import org.oblivios.decoy.content.persona.Question;
import org.oblivios.decoy.content.persona.WorkPattern;
import org.oblivios.decoy.content.story.PlaceKind;
import org.oblivios.decoy.content.surface.Relation;
import org.oblivios.decoy.content.world.World;
import org.oblivios.decoy.content.world.WorldBuilder;
import org.oblivios.decoy.content.world.WorldPerson;
import org.oblivios.decoy.content.world.WorldPlace;

/**
 * The weekly pattern of the persona. {@code weeks} is the rotation, of length 1 when the hours are fixed; sleep is not
 * in it, because it depends on the real day before and after. {@code wakeWorkday} and {@code sleepWorkday} are those of
 * the first working day of the first week, so a night worker's are by day. {@code wakeFree} and {@code sleepFree} are
 * the persona's own times for a day without work, school or a child to bring.
 *
 * <p>No source gives sleep, travel, school or care hours: every such figure of this class is {@code [assumption]}.
 */
public record Routine(List<Map<DayOfWeek, List<Block>>> weeks, LocalTime wakeWorkday, LocalTime wakeFree,
        LocalTime sleepWorkday, LocalTime sleepFree, Origin origin) {

    private static final int DAY = 24 * 60;
    private static final int NOON = 12 * 60;
    /** A block of the next day that starts before this keeps the persona up; it does not wake them. */
    private static final int STILL_UP = 4 * 60;
    /** Awake between coming home and sleeping by day. */
    private static final int WIND_DOWN = 45;
    /** The length of a sleep that nothing cuts short. */
    private static final int SLEEP = 7 * 60 + 30;
    private static final int SHORTEST_NIGHT = 6 * 60 + 30;
    private static final int SHORTEST_SLEEP = 3 * 60;
    /** A rotating shift starts this much later each week, so three weeks go round the day. */
    private static final int ROTATION = 8 * 60;
    private static final LocalTime SCHOOL_BRING = LocalTime.of(8, 30);
    private static final LocalTime SCHOOL_FETCH = LocalTime.of(14, 0);
    private static final LocalTime CARE_BRING = LocalTime.of(8, 0);
    private static final LocalTime CARE_FETCH = LocalTime.of(17, 30);
    /** Younger children go to care, older ones to school until the age at which they go alone. */
    private static final int SCHOOL_AGE = 4;
    private static final int GOES_ALONE = 12;
    private static final Set<DayOfWeek> SCHOOL_WEEK = EnumSet.range(DayOfWeek.MONDAY, DayOfWeek.FRIDAY);
    private static final LocalDate A_MONDAY = LocalDate.of(2024, 1, 1);

    public Routine {
        Objects.requireNonNull(origin, "origin");
        weeks = weeks.stream().map(Map::copyOf).toList();
    }

    /** The blocks of the pattern on a date, before any exception. */
    List<Block> on(LocalDate date) {
        long week = ChronoUnit.WEEKS.between(A_MONDAY, date.with(DayOfWeek.MONDAY));
        return weeks.get((int) Math.floorMod(week, (long) weeks.size())).getOrDefault(date.getDayOfWeek(), List.of());
    }

    /** The minutes of the way home of a shift that ends after midnight, which is as long as the way there; else 0. */
    static int wayHomeAfterMidnight(List<Block> blocks) {
        for (Block work : blocks) {
            if (work.work() && work.to() >= DAY) {
                return blocks.stream().filter(b -> b.kind() == EventKind.COMMUTE && b.to() == work.from())
                        .mapToInt(b -> b.to() - b.from()).findFirst().orElse(0);
            }
        }
        return 0;
    }

    /**
     * The sleep that follows a day, as minutes from that day's midnight: by day after a shift that ends in the morning,
     * else the night to the next day. {@code today} and {@code next} are the blocks that hold on those two days.
     */
    int[] sleepAfter(List<Block> today, List<Block> next, Own own) {
        int lastEnd = today.stream().mapToInt(Block::to).max().orElse(0) + wayHomeAfterMidnight(today);
        if (today.stream().anyMatch(b -> b.work() && b.to() > DAY && b.to() <= DAY + NOON)) {
            int bed = lastEnd + WIND_DOWN;
            // a gap too short to sleep in is not slept in: the persona stays up through what starts that soon
            for (Block b : next) {
                if (DAY + b.from() < bed + SHORTEST_SLEEP) {
                    bed = Math.max(bed, DAY + b.to() + WIND_DOWN);
                }
            }
            int wake = bed + SLEEP;
            for (Block b : next) {
                if (DAY + b.from() >= bed) {
                    wake = Math.min(wake, DAY + b.from() - 30);
                }
            }
            return new int[] {bed, wake};
        }
        boolean free = next.stream().noneMatch(b -> b.work() || b.kind() == EventKind.BRING_CHILD);
        int wake = DAY + minutes(free ? wakeFree : own.wake());
        for (Block b : next) {
            if (b.from() < STILL_UP) {
                lastEnd = Math.max(lastEnd, DAY + b.to());
            } else {
                wake = Math.min(wake, DAY + b.from() - own.prepare());
            }
        }
        int bed = minutes(free ? sleepFree : own.sleep());
        bed = bed < NOON ? bed + DAY : bed;
        if (wake - bed < SHORTEST_NIGHT) {
            bed = wake - SLEEP;
        }
        bed = Math.max(bed, lastEnd + 15);
        return new int[] {bed, wake > bed ? wake : bed + SHORTEST_SLEEP};
    }

    /** The persona's own times on a working day, and how long they take to get ready. */
    record Own(LocalTime wake, LocalTime sleep, int prepare) {}

    private static int minutes(LocalTime t) {
        return t.toSecondOfDay() / 60;
    }

    private static LocalTime time(int minutes) {
        return LocalTime.ofSecondOfDay(Math.floorMod(minutes, DAY) * 60L);
    }

    /** The routine of the persona and the own times it is built from. */
    record Drawn(Routine routine, Own own) {}

    static Drawn of(Persona p, World w, PlaceData places, Draws draws) {
        RandomGenerator r = draws.stream("chronicle.routine");
        int wake = 7 * 60 + 15 * r.nextInt(7);
        int bed = 22 * 60 + 30 + 15 * r.nextInt(6);
        LocalTime wakeFree = time(wake + 60 + 30 * r.nextInt(3));
        LocalTime sleepFree = time(bed + 30 + 30 * r.nextInt(3));
        Own own = new Own(time(wake), time(bed), 40 + 5 * r.nextInt(5));

        int rotation = p.work().rotating() ? 3 : 1;
        int offset = r.nextInt(rotation);
        List<Map<DayOfWeek, List<Block>>> weeks = new ArrayList<>();
        for (int i = 0; i < rotation; i++) {
            Map<DayOfWeek, List<Block>> week = new EnumMap<>(DayOfWeek.class);
            for (DayOfWeek day : DayOfWeek.values()) {
                List<Block> blocks = new ArrayList<>(work(p, w, day, (i + offset) % rotation * ROTATION));
                activities(p, w, places, day, blocks);
                children(p, w, day, blocks);
                blocks.sort(Comparator.comparingInt(Block::from));
                week.put(day, List.copyOf(blocks));
            }
            weeks.add(week);
        }
        Routine times = new Routine(weeks, own.wake(), wakeFree, own.sleep(), sleepFree, Origin.world(w.self().id()));
        return new Drawn(times.withWorkdayTimes(own), own);
    }

    private Routine withWorkdayTimes(Own own) {
        for (int i = 0; i < 7; i++) {
            LocalDate day = A_MONDAY.plusDays(i);
            List<Block> blocks = on(day);
            Optional<Block> first = blocks.stream().filter(Block::work).findFirst();
            if (first.isEmpty()) {
                continue;
            }
            boolean night = first.get().to() > DAY && first.get().to() <= DAY + NOON;
            int[] sleep = night ? sleepAfter(blocks, on(day.plusDays(1)), own) : sleepAfter(on(day.minusDays(1)), blocks, own);
            return new Routine(weeks, time(sleep[1]), wakeFree, time(sleep[0]), sleepFree, origin);
        }
        return this;
    }

    private static List<Block> work(Persona p, World w, DayOfWeek day, int later) {
        WorkPattern pattern = p.work();
        if (!pattern.days().contains(day)) {
            return List.of();
        }
        boolean learns = p.job() == JobKind.PUPIL || p.job() == JobKind.STUDENT;
        PlaceKind kind = learns ? PlaceKind.SCHOOL : p.job() == JobKind.CARER ? PlaceKind.HOME : PlaceKind.WORK;
        Optional<WorldPlace> place = first(w, kind);
        LocalTime start = pattern.start().plusMinutes(later);
        LocalTime end = pattern.end().plusMinutes(later);
        Origin origin = Origin.answer(Question.WORK_PATTERN.name());
        List<Block> out = new ArrayList<>();
        out.add(new Block(learns ? EventKind.SCHOOL : EventKind.WORK, start, end, place.map(WorldPlace::id).orElse(""), List.of(),
                origin));
        if (place.isPresent() && kind != PlaceKind.HOME) {
            int travel = travel(w, place.get(), p.transport());
            // shortcut: a way that would start before the midnight of its day is left out, as a block starts on its day
            if (minutes(start) >= travel) {
                out.add(new Block(EventKind.COMMUTE, start.minusMinutes(travel), start, place.get().id(), List.of(), origin));
            }
            // a block starts on its day, so the way home after midnight is not a block: see wayHomeAfterMidnight
            if (out.get(0).to() < DAY) {
                out.add(new Block(EventKind.COMMUTE, end, end.plusMinutes(travel), WorldBuilder.HOME, List.of(), origin));
            }
        }
        return out;
    }

    private static void activities(Persona p, World w, PlaceData places, DayOfWeek day, List<Block> blocks) {
        for (ActivityAnswer a : p.activities()) {
            if (a.day() != day) {
                continue;
            }
            Block block = new Block(EventKind.ACTIVITY, a.start(), a.start().plusMinutes(a.minutes()), activityPlace(w, places, a),
                    List.of(), Origin.answer(Question.ACTIVITIES.name()));
            if (blocks.stream().noneMatch(block::overlaps)) {
                blocks.add(block);
            }
        }
    }

    private static String activityPlace(World w, PlaceData places, ActivityAnswer a) {
        Venue.VenueKind kind = switch (a.kind()) {
            case TEAM_SPORT, GYM, SWIMMING -> Venue.VenueKind.SPORT;
            case MUSIC, CHOIR -> Venue.VenueKind.MUSIC;
            case FAITH -> Venue.VenueKind.FAITH;
            case RUNNING, CLASS, VOLUNTEERING, GAMING, CLUB -> null;
        };
        if (kind == null) {
            return "";
        }
        return w.places().stream().filter(x -> places.venues(x.municipality(), kind).stream().anyMatch(v -> v.id().equals(x.id())))
                .map(WorldPlace::id).findFirst().orElse("");
    }

    /** Bringing and fetching, where the persona's own day leaves room for it. */
    private static void children(Persona p, World w, DayOfWeek day, List<Block> blocks) {
        List<WorldPerson> children = w.people().stream().filter(x -> x.relation() == Relation.CHILD).toList();
        for (PlaceKind kind : new PlaceKind[] {PlaceKind.CARE, PlaceKind.SCHOOL}) {
            Optional<WorldPlace> place = first(w, kind);
            if (place.isEmpty()) {
                continue;
            }
            List<String> going = new ArrayList<>();
            for (int i = 0; i < p.children().size(); i++) {
                ChildAnswer c = p.children().get(i); // the world holds the children in the order of the answers
                boolean here = kind == PlaceKind.CARE ? c.age() < SCHOOL_AGE : c.age() >= SCHOOL_AGE && c.age() < GOES_ALONE;
                boolean goes = switch (c.custody()) {
                    case HOME -> kind == PlaceKind.SCHOOL ? SCHOOL_WEEK.contains(day)
                            : SCHOOL_WEEK.contains(day) && p.work().days().contains(day);
                    case SHARED -> c.daysWithPersona().contains(day) && (kind == PlaceKind.CARE || SCHOOL_WEEK.contains(day));
                    case ELSEWHERE -> false;
                };
                if (here && goes && i < children.size()) {
                    going.add(children.get(i).id());
                }
            }
            if (going.isEmpty()) {
                continue;
            }
            int travel = travel(w, place.get(), p.transport());
            for (EventKind way : new EventKind[] {EventKind.BRING_CHILD, EventKind.FETCH_CHILD}) {
                LocalTime at = way == EventKind.BRING_CHILD ? (kind == PlaceKind.CARE ? CARE_BRING : SCHOOL_BRING)
                        : (kind == PlaceKind.CARE ? CARE_FETCH : SCHOOL_FETCH);
                Block block = new Block(way, at.minusMinutes(travel), at.plusMinutes(travel), place.get().id(), going,
                        Origin.world(going.get(0)));
                // a shift of the day before may still run in the morning; the sleep after it begins after this
                if (blocks.stream().noneMatch(block::overlaps)) {
                    blocks.add(block);
                }
            }
        }
    }

    private static Optional<WorldPlace> first(World w, PlaceKind kind) {
        return w.places().stream().filter(x -> x.kind() == kind).findFirst();
    }

    /** Minutes from home: the straight line and a third more for the road, at the speed of the transport. */
    private static int travel(World w, WorldPlace to, Transport transport) {
        WorldPlace home = w.places().get(0);
        double north = (to.lat() - home.lat()) * 111.2;
        double east = (to.lon() - home.lon()) * 111.2 * Math.cos(Math.toRadians(home.lat()));
        double km = Math.hypot(north, east) * 4 / 3;
        double perHour = switch (transport) {
            case CAR -> 30;
            case BIKE -> 15;
            case PUBLIC -> 18;
        };
        int overhead = switch (transport) {
            case CAR -> 5;
            case BIKE -> 2;
            case PUBLIC -> 12;
        };
        return (int) Math.max(5, Math.min(90, Math.round(km / perHour * 60) + overhead));
    }
}
