// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.data;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.random.RandomGenerator;

/**
 * The places of one country, read from {@code places/<CC>/}: its municipalities, the squares of 100 metres where their
 * people live, and venues by name and position.
 */
public final class PlaceData {
    private static final Map<String, PlaceData> LOADED = new ConcurrentHashMap<>();
    /** How much of a typed city is read: no name is longer, and a refusal must not carry a long input on. */
    private static final int LONGEST = 60;
    /** Half a square less a metre, so that a point stays in its square after rounding. */
    private static final double HALF = 49.0;
    private static final double METRES_PER_DEGREE = 111_320.0;

    /** The squares of one municipality, with the running total of their inhabitants for a weighted draw. */
    private record Squares(double[] lat, double[] lon, long[] upTo) {}

    private record City(CityRef ref, List<String> names) {}

    private final Map<String, City> cities = new LinkedHashMap<>();
    private final Map<String, List<City>> byName = new HashMap<>();
    private final Map<String, Squares> squares = new HashMap<>();
    private final Map<String, Map<Venue.VenueKind, List<Venue>>> venues = new HashMap<>();
    private final String version;

    private PlaceData(String country) {
        String dir = "places/" + country + "/";
        String cityVersion = Tables.read(dir + "cities.tsv", Tables.open(dir + "cities.tsv"), 6, c -> {
            List<String> names = new ArrayList<>(List.of(c[1]));
            if (!c[5].isEmpty()) {
                names.addAll(Arrays.asList(c[5].split("\\|")));
            }
            City city = new City(new CityRef(c[0], c[1], Double.parseDouble(c[2]), Double.parseDouble(c[3]), c[4]),
                    List.copyOf(names));
            cities.put(c[0], city);
            names.forEach(n -> byName.computeIfAbsent(key(n), k -> new ArrayList<>()).add(city));
        });
        Map<String, List<double[]>> read = new HashMap<>();
        Tables.read(dir + "points.tsv.gz", Tables.open(dir + "points.tsv.gz"), 4,
                c -> read.computeIfAbsent(c[0], k -> new ArrayList<>()).add(
                        new double[] {Double.parseDouble(c[1]), Double.parseDouble(c[2]), Long.parseLong(c[3])}));
        read.forEach((municipality, list) -> {
            double[] lat = new double[list.size()];
            double[] lon = new double[list.size()];
            long[] upTo = new long[list.size()];
            long sum = 0;
            for (int i = 0; i < upTo.length; i++) {
                lat[i] = list.get(i)[0];
                lon[i] = list.get(i)[1];
                sum += (long) list.get(i)[2];
                upTo[i] = sum;
            }
            squares.put(municipality, new Squares(lat, lon, upTo));
        });
        String venueVersion = Tables.read(dir + "venues.tsv.gz", Tables.open(dir + "venues.tsv.gz"), 5, c -> {
            Venue.VenueKind kind = Venue.VenueKind.valueOf(c[1]);
            List<Venue> list = venues.computeIfAbsent(c[0], k -> new EnumMap<>(Venue.VenueKind.class))
                    .computeIfAbsent(kind, k -> new ArrayList<>());
            list.add(new Venue("venue." + c[0] + "." + kind + "." + list.size(), kind, c[2],
                    Double.parseDouble(c[3]), Double.parseDouble(c[4])));
        });
        version = cityVersion + "; " + venueVersion;
    }

    /** Throws {@link IllegalArgumentException} for a country without place data: no other places stand in. */
    public static PlaceData load(String country) {
        if (!Tables.has(country, "places", "{cc}/cities.tsv")) {
            throw new IllegalArgumentException("no place data for country " + country);
        }
        return LOADED.computeIfAbsent(country, PlaceData::new);
    }

    /**
     * The city the setup was given, or {@link IllegalArgumentException} whose message starts with {@code city: } and,
     * where the country has places, names the three nearest ones. A city is never replaced.
     */
    public static CityRef require(String country, String typed) {
        String shown = typed == null ? "" : typed.substring(0, Math.min(typed.length(), LONGEST));
        if (!Tables.has(country, "places", "{cc}/cities.tsv")) {
            throw new IllegalArgumentException("city: no place data for '" + shown + "' in country " + country);
        }
        PlaceData data = load(country);
        return data.city(typed).orElseThrow(() -> new IllegalArgumentException(
                "city: unknown '" + shown + "', did you mean: " + String.join(", ", data.suggest(shown, 3))));
    }

    /** Lower case without spaces, hyphens and apostrophes. */
    private static String key(String name) {
        StringBuilder out = new StringBuilder();
        name.toLowerCase(Locale.ROOT).codePoints()
                .filter(c -> !Character.isWhitespace(c) && c != '-' && c != '\'' && c != '’')
                .forEach(out::appendCodePoint);
        return out.toString();
    }

    /** The one municipality that bears the name; empty for no municipality and for a name that two bear. */
    public Optional<CityRef> city(String name) {
        if (name == null || name.length() > LONGEST) {
            return Optional.empty();
        }
        List<City> found = byName.getOrDefault(key(name), List.of());
        return found.size() == 1 ? Optional.of(found.get(0).ref()) : Optional.empty();
    }

    /** At most {@code max} names that {@link #city} accepts, the nearest by edit distance first. */
    public List<String> suggest(String typed, int max) {
        String wanted = key(typed.substring(0, Math.min(typed.length(), LONGEST)));
        record Near(int distance, String name) {}
        List<Near> all = new ArrayList<>();
        for (City city : cities.values()) {
            // of the city's names, one that no other city bears, the usual one first
            String own = city.names().stream().filter(n -> byName.get(key(n)).size() == 1).findFirst()
                    .orElse(city.ref().name());
            int distance = city.names().stream().mapToInt(n -> distance(wanted, key(n))).min().orElseThrow();
            all.add(new Near(distance, own));
        }
        all.sort(Comparator.comparingInt(Near::distance).thenComparing(Near::name));
        Set<String> out = new LinkedHashSet<>();
        for (int i = 0; i < all.size() && out.size() < max; i++) {
            out.add(all.get(i).name());
        }
        return List.copyOf(out);
    }

    /** The edit distance of Levenshtein. */
    private static int distance(String a, String b) {
        int[] row = new int[b.length() + 1];
        for (int j = 0; j < row.length; j++) {
            row[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            int diagonal = row[0];
            row[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int above = row[j];
                row[j] = Math.min(Math.min(above, row[j - 1]) + 1,
                        diagonal + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1));
                diagonal = above;
            }
        }
        return row[b.length()];
    }

    /**
     * {@code {lat, lon}} of a point where people of the municipality live: a square weighted by its inhabitants, and a
     * point inside it.
     */
    public double[] point(String municipality, RandomGenerator r) {
        Squares s = squares.get(municipality);
        if (s == null) {
            throw new IllegalArgumentException("no places for municipality " + municipality);
        }
        long pick = r.nextLong(s.upTo()[s.upTo().length - 1]);
        int at = Arrays.binarySearch(s.upTo(), pick + 1);
        int i = at >= 0 ? at : -at - 1;
        double north = (r.nextDouble() * 2 - 1) * HALF;
        double east = (r.nextDouble() * 2 - 1) * HALF;
        return new double[] {s.lat()[i] + north / METRES_PER_DEGREE,
                s.lon()[i] + east / (METRES_PER_DEGREE * Math.cos(Math.toRadians(s.lat()[i])))};
    }

    public List<Venue> venues(String municipality, Venue.VenueKind kind) {
        Map<Venue.VenueKind, List<Venue>> of = venues.get(municipality);
        return of == null ? List.of() : List.copyOf(of.getOrDefault(kind, List.of()));
    }

    public String version() {
        return version;
    }
}
