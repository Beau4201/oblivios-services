// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.chronicle;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Month;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.random.RandomGenerator;
import java.util.stream.Stream;
import org.oblivios.decoy.content.Draws;
import org.oblivios.decoy.content.country.CountryPack;
import org.oblivios.decoy.content.country.Sender;
import org.oblivios.decoy.content.country.SenderKind;
import org.oblivios.decoy.content.data.Spreads;
import org.oblivios.decoy.content.origin.Origin;
import org.oblivios.decoy.content.story.PlaceKind;
import org.oblivios.decoy.content.surface.Shuffle;
import org.oblivios.decoy.content.surface.Trip;
import org.oblivios.decoy.content.world.World;
import org.oblivios.decoy.content.world.WorldBuilder;
import org.oblivios.decoy.content.world.WorldPerson;
import org.oblivios.decoy.content.world.WorldPlace;

/**
 * The small events of a life, around everything the earlier layers hold: shop visits and what is paid there, online
 * orders with their payment and delivery, log-ins, and short calls and messages by the closeness of people. How often
 * is a range of the spreads scaled by the persona's own activity; what a day raises or lowers, how long a visit lasts,
 * when shops are open and when parcels come is {@code [assumption]}. An event done on the phone, and a delivery, takes
 * no time: it ends when it starts.
 */
final class SmallEvents {
    private static final int SHOP_OPENS = 8;
    private static final int SHOP_CLOSES = 21;
    private static final int SHORTEST_VISIT = 10;
    private static final int LONGEST_VISIT = 45;
    private static final Duration THERE_AND_BACK = Duration.ofMinutes(10);
    private static final int VISIT_TRIES = 20;
    private static final double AT_THE_SUPERMARKET = 0.7;
    private static final int LONGEST_DELIVERY_DAYS = 4;
    private static final int FIRST_DELIVERY_HOUR = 9;
    private static final int LAST_DELIVERY_HOUR = 20;
    private static final int LONGEST_CHECKOUT_SECONDS = 150;
    /** Two calls or messages closer than this belong together. */
    private static final Duration SAME_BURST = Duration.ofMinutes(10);
    private static final double SAME_THREAD = 0.8;

    private final Chronicle before;
    private final World world;
    private final CountryPack pack;
    private final Spreads spreads;
    private final Draws draws;
    private final ZoneId home;
    private final List<LocalDate> days;
    private final Map<LocalDate, Set<EventKind>> marks = new HashMap<>();
    private final List<Event> out = new ArrayList<>();
    private final Map<String, Integer> counts = new HashMap<>();

    private SmallEvents(Chronicle before, World world, CountryPack pack, Spreads spreads, Draws draws) {
        this.before = before;
        this.world = world;
        this.pack = pack;
        this.spreads = spreads;
        this.draws = draws;
        home = before.zone();
        days = LocalDate.ofInstant(before.phoneStart(), home)
                .datesUntil(LocalDate.ofInstant(before.now(), home).plusDays(1)).toList();
        for (Event e : before.events()) {
            if (e.kind().marksDays()) {
                marks.computeIfAbsent(LocalDate.ofInstant(e.start(), e.zone()), d -> EnumSet.noneOf(EventKind.class))
                        .add(e.kind());
            }
        }
    }

    /** The small events around {@code before}, which holds the earlier layers; they carry no traces yet. */
    static List<Event> add(Chronicle before, World world, CountryPack pack, Spreads spreads, Draws draws) {
        SmallEvents made = new SmallEvents(before, world, pack, spreads, draws);
        made.shopVisits();
        // from here on a shop visit is time that is taken
        Availability free = new Availability(Stream.concat(before.events().stream(), made.out.stream()).toList());
        made.orders(free);
        made.logins(free);
        made.contacts(EventKind.CALL, "small.calls.perWeek", world.habits().activity().calls(),
                world.habits().callBackShare(), free);
        made.contacts(EventKind.MESSAGE, "small.messages.perWeek", world.habits().activity().texts(), SAME_THREAD, free);
        return made.out;
    }

    private void shopVisits() {
        List<WorldPlace> shops = world.places().stream().filter(p -> p.kind() == PlaceKind.SHOP).toList();
        List<WorldPlace> supermarkets = world.places().stream().filter(p -> p.kind() == PlaceKind.SUPERMARKET).toList();
        if (shops.isEmpty() && supermarkets.isEmpty()) {
            return;
        }
        double perDay = rate("small.shopVisits.perWeek", 1) / 7;
        Availability free = before.availability();
        for (LocalDate day : days) {
            if (trip(day) != null) {
                continue;
            }
            List<Event> taken = new ArrayList<>();
            for (int n = count(EventKind.SHOP_VISIT, day, perDay * shopping(day)); n > 0; n--) {
                RandomGenerator r = stream(EventKind.SHOP_VISIT, day, "visit." + n);
                for (int tries = 0; tries < VISIT_TRIES; tries++) {
                    List<Instant> at = Bursts.times(1, day, home, world.habits().rhythm(), free, r);
                    if (at.isEmpty()) {
                        break;
                    }
                    Instant start = at.get(0);
                    Instant end = start.plus(Duration.ofMinutes(r.nextInt(SHORTEST_VISIT, LONGEST_VISIT + 1)));
                    int hour = start.atZone(home).getHour();
                    boolean open = hour >= SHOP_OPENS && end.atZone(home).getHour() < SHOP_CLOSES;
                    boolean apart = taken.stream().noneMatch(v -> v.start().isBefore(end.plus(THERE_AND_BACK))
                            && v.end().isAfter(start.minus(THERE_AND_BACK)));
                    if (!open || !apart || end.isAfter(before.now())
                            || !free.free(start.minus(THERE_AND_BACK), end.plus(THERE_AND_BACK))) {
                        continue;
                    }
                    List<WorldPlace> from = shops.isEmpty()
                            || !supermarkets.isEmpty() && r.nextDouble() < AT_THE_SUPERMARKET ? supermarkets : shops;
                    WorldPlace shop = from.get(r.nextInt(from.size()));
                    Event visit = event(EventKind.SHOP_VISIT, day, start, end, home, shop.id(), List.of(), shop.name(),
                            Origin.world(shop.id()));
                    if (visit != null) {
                        taken.add(visit);
                        Instant paid = end.minusSeconds(r.nextInt(30, 121));
                        event(EventKind.PAYMENT, day, paid, paid, home, shop.id(), List.of(), shop.name(),
                                Origin.event(visit.id()));
                    }
                    break;
                }
            }
        }
    }

    private void orders(Availability free) {
        List<Sender> shops = pack.senders(SenderKind.SHOP);
        List<Sender> carriers = pack.senders(SenderKind.PARCEL);
        if (shops.isEmpty() || carriers.isEmpty()) {
            return;
        }
        double perDay = rate("small.orders.perMonth", world.habits().activity().browsing()) * 12 / 365;
        for (LocalDate day : days) {
            double factor = day.getMonth() == Month.DECEMBER ? 1.4 : 1;
            RandomGenerator r = stream(EventKind.ORDER, day, "items");
            for (Instant at : Bursts.times(count(EventKind.ORDER, day, perDay * factor), day, zone(day),
                    world.habits().rhythm(), free, r)) {
                String shop = shops.get(r.nextInt(shops.size())).name();
                Event order = event(EventKind.ORDER, day, at, at, zone(day), "", List.of(), shop, Origin.world(world.self().id()));
                if (order == null) {
                    continue;
                }
                Origin of = Origin.event(order.id());
                Instant paid = at.plusSeconds(r.nextInt(20, LONGEST_CHECKOUT_SECONDS));
                event(EventKind.PAYMENT, day, paid, paid, zone(day), "", List.of(), shop, of);
                LocalDate comes = day.plusDays(1 + r.nextInt(LONGEST_DELIVERY_DAYS));
                while (trip(comes) != null) {
                    comes = comes.plusDays(1); // nobody is there to take it in during a trip
                }
                Instant door = comes.atTime(r.nextInt(FIRST_DELIVERY_HOUR, LAST_DELIVERY_HOUR), 0).atZone(home).toInstant()
                        .plusMillis(r.nextInt(3_600_000));
                event(EventKind.DELIVERY, comes, door, door, home, WorldBuilder.HOME, List.of(),
                        carriers.get(r.nextInt(carriers.size())).name(), of);
            }
        }
    }

    private void logins(Availability free) {
        List<String> services = new ArrayList<>(List.of(world.choices().bank(), world.choices().operator()));
        pack.senders(SenderKind.GLOBAL).forEach(s -> services.add(s.name()));
        double perDay = rate("small.logins.perMonth", world.habits().activity().browsing()) * 12 / 365;
        for (LocalDate day : days) {
            RandomGenerator r = stream(EventKind.LOGIN, day, "items");
            for (Instant at : Bursts.times(count(EventKind.LOGIN, day, perDay), day, zone(day), world.habits().rhythm(),
                    free, r)) {
                event(EventKind.LOGIN, day, at, at, zone(day), "", List.of(), services.get(r.nextInt(services.size())),
                        Origin.world(world.self().id()));
            }
        }
    }

    /**
     * Calls or messages. A share of the people, from the farther half, is never reached: a real phone holds contacts
     * nobody calls. Within a burst the same person comes back with the chance {@code again}.
     */
    private void contacts(EventKind kind, String key, double activity, double again, Availability free) {
        List<WorldPerson> reachable = new ArrayList<>(world.people().stream().filter(p -> p.phone() != null)
                .sorted(Comparator.comparingInt(WorldPerson::closeness)).toList());
        List<WorldPerson> farther = new ArrayList<>(reachable.subList(reachable.size() / 2, reachable.size()));
        // the same stream for calls and messages, so both leave out the same people
        Shuffle.inPlace(farther, draws.stream("small.unused"));
        int unused = (int) Math.round(reachable.size() * world.habits().tidiness().unusedContacts());
        reachable.removeAll(farther.subList(0, Math.min(unused, farther.size())));
        if (reachable.isEmpty()) {
            return;
        }
        double[] upTo = new double[reachable.size()];
        double sum = 0;
        for (int i = 0; i < upTo.length; i++) {
            sum += Math.pow(i + 1, -world.habits().rankExponent());
            upTo[i] = sum;
        }
        double perDay = rate(key, activity) / 7;
        for (LocalDate day : days) {
            RandomGenerator r = stream(kind, day, "items");
            Instant last = null;
            WorldPerson with = null;
            for (Instant at : Bursts.times(count(kind, day, perDay * phone(day)), day, zone(day), world.habits().rhythm(),
                    free, r)) {
                if (last == null || Duration.between(last, at).compareTo(SAME_BURST) > 0 || r.nextDouble() >= again) {
                    double roll = r.nextDouble() * sum;
                    int i = 0;
                    while (i < upTo.length - 1 && roll >= upTo[i]) {
                        i++;
                    }
                    with = reachable.get(i);
                }
                last = at;
                event(kind, day, at, at, zone(day), "", List.of(with.id()), "", Origin.world(with.id()));
            }
        }
    }

    /** What a day does to calls and messages. */
    private double phone(LocalDate day) {
        double f = trip(day) != null ? 0.5 : 1;
        f *= weekend(day) || marked(day, EventKind.PUBLIC_HOLIDAY) ? 1.2 : 0.95;
        f *= marked(day, EventKind.BIRTHDAY) ? 1.2 : 1;
        f *= marked(day, EventKind.SICK) ? 0.8 : 1;
        return f * (day.getMonth() == Month.JULY || day.getMonth() == Month.AUGUST ? 0.9 : 1);
    }

    /** What a day does to shop visits; a week without anything else adds up to seven days. */
    private double shopping(LocalDate day) {
        double f = day.getDayOfWeek() == DayOfWeek.SATURDAY ? 1.6 : day.getDayOfWeek() == DayOfWeek.SUNDAY ? 0.5 : 0.98;
        f *= marked(day, EventKind.PUBLIC_HOLIDAY) ? 0.5 : 1;
        return f * (marked(day, EventKind.SICK) ? 0.3 : 1);
    }

    private static boolean weekend(LocalDate day) {
        return day.getDayOfWeek() == DayOfWeek.SATURDAY || day.getDayOfWeek() == DayOfWeek.SUNDAY;
    }

    private boolean marked(LocalDate day, EventKind kind) {
        return marks.getOrDefault(day, Set.of()).contains(kind);
    }

    private Trip trip(LocalDate day) {
        return before.trips().stream().filter(t -> !day.isBefore(LocalDate.ofInstant(t.from(), home))
                && !day.isAfter(LocalDate.ofInstant(t.to(), home))).findFirst().orElse(null);
    }

    private ZoneId zone(LocalDate day) {
        Trip t = trip(day);
        return t == null ? home : t.zone();
    }

    /** The persona's own amount, drawn once from the range of the spreads. */
    private double rate(String key, double activity) {
        return spreads.draw(key, draws.stream("small.rate." + key)) * activity;
    }

    /** A count of one day around its mean (Poisson). */
    private int count(EventKind kind, LocalDate day, double mean) {
        RandomGenerator r = stream(kind, day, "count");
        double limit = Math.exp(-mean);
        double product = r.nextDouble();
        int n = 0;
        while (product > limit) {
            product *= r.nextDouble();
            n++;
        }
        return n;
    }

    private RandomGenerator stream(EventKind kind, LocalDate day, String what) {
        return draws.stream("small." + kind.name().toLowerCase(Locale.ROOT) + "." + day + "." + what);
    }

    /** The new event, or {@code null} for one outside the time of the phone; an empty company is left out. */
    private Event event(EventKind kind, LocalDate day, Instant start, Instant end, ZoneId zone, String placeId,
            List<String> people, String company, Origin origin) {
        if (!start.isBefore(before.now()) || start.isBefore(before.phoneStart())) {
            return null;
        }
        String name = "event.small." + kind.name().toLowerCase(Locale.ROOT) + "." + day;
        Event e = new Event(name + "." + counts.merge(name, 1, Integer::sum), kind, start, end, zone, placeId, people,
                Event.Layer.SMALL, Set.of(), company.isEmpty() ? Map.of() : Map.of("company", company), origin);
        out.add(e);
        return e;
    }
}
