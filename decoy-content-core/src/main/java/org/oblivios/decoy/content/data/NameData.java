// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.data;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.random.RandomGenerator;
import org.oblivios.decoy.content.persona.PersonaProfile.Gender;

/**
 * The names of one family background, read from {@code names/<BG>.tsv}: given names by sex and birth period, and
 * family names, each with a weight. The sources differ in size, so a weight counts only as a share of its period.
 */
public final class NameData {
    private static final String DIR = "/org/oblivios/decoy/content/data/names/";
    private static final Map<String, NameData> LOADED = new ConcurrentHashMap<>();
    /** A given name is drawn only where it occurs at least this much, against its own largest period. */
    private static final double COHORT = 0.05;
    private static final double ONE_SEX = 0.9;

    private record Entry(String name, long weight) {}

    /** Entries with their running totals, for a weighted draw. */
    private record Table(List<Entry> entries, long[] upTo) {
        static Table of(List<Entry> entries) {
            long[] upTo = new long[entries.size()];
            long sum = 0;
            for (int i = 0; i < upTo.length; i++) {
                sum += entries.get(i).weight();
                upTo[i] = sum;
            }
            return new Table(List.copyOf(entries), upTo);
        }

        long total() {
            return upTo.length == 0 ? 0 : upTo[upTo.length - 1];
        }

        String draw(RandomGenerator r) {
            long pick = r.nextLong(total());
            int low = 0;
            int high = upTo.length - 1;
            while (low < high) {
                int middle = (low + high) >>> 1;
                if (pick < upTo[middle]) {
                    high = middle;
                } else {
                    low = middle + 1;
                }
            }
            return entries.get(low).name();
        }
    }

    private record Period(int from, int to, Map<String, Long> weights, long total) {
        double share(String name) {
            return weights.getOrDefault(name, 0L) / (double) total;
        }

        int distance(int year) {
            return year < from ? from - year : year > to ? year - to : 0;
        }
    }

    private final String background;
    private final String version;
    private final Map<Gender, List<Period>> periods;
    private final Map<Gender, Table> families = new EnumMap<>(Gender.class);
    private final Map<Gender, Map<String, Double>> largestShare = new EnumMap<>(Gender.class);
    private final Map<Gender, Map<String, Double>> summedShare = new EnumMap<>(Gender.class);
    private final Map<Period, Table> drawable = new ConcurrentHashMap<>();

    private NameData(String background, String version, Map<Gender, List<Period>> periods, Map<Gender, List<Entry>> family) {
        this.background = background;
        this.version = version;
        this.periods = periods;
        for (Gender g : Gender.values()) {
            families.put(g, Table.of(family.get(g)));
            Map<String, Double> largest = new HashMap<>();
            Map<String, Double> summed = new HashMap<>();
            for (Period p : periods.get(g)) {
                for (String name : p.weights().keySet()) {
                    largest.merge(name, p.share(name), Math::max);
                    summed.merge(name, p.share(name), Double::sum);
                }
            }
            largestShare.put(g, largest);
            summedShare.put(g, summed);
        }
    }

    /** Throws {@link IllegalArgumentException} for a background without a name file: no other names stand in. */
    public static NameData load(String background) {
        if (!backgrounds().contains(background)) {
            throw new IllegalArgumentException("no name data for background " + background);
        }
        return LOADED.computeIfAbsent(background, code -> {
            try (InputStream in = NameData.class.getResourceAsStream(DIR + code + ".tsv")) {
                if (in == null) {
                    throw new IllegalStateException("names/index.properties lists " + code + ", which has no file");
                }
                return parse(code, new InputStreamReader(in, StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    /** The backgrounds that have a name file, as {@code names/index.properties} lists them. */
    public static Set<String> backgrounds() {
        try (InputStream in = NameData.class.getResourceAsStream(DIR + "index.properties")) {
            if (in == null) {
                throw new IllegalStateException("names/index.properties is missing");
            }
            Properties p = new Properties();
            p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            Set<String> out = new HashSet<>();
            for (String code : p.getProperty("backgrounds", "").trim().split("\\s+")) {
                out.add(code);
            }
            return Set.copyOf(out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static NameData parse(String background, Reader reader) {
        try {
            BufferedReader in = new BufferedReader(reader);
            List<String> head = new ArrayList<>();
            for (String start : List.of("# source: ", "# licence: ", "# version: ")) {
                String line = in.readLine();
                if (line == null || !line.startsWith(start)) {
                    throw new IllegalStateException("names " + background + ": the line '" + start + "...' is missing");
                }
                head.add(line.substring(start.length()));
            }
            Map<Gender, Map<Integer, Map<String, Long>>> given = new EnumMap<>(Gender.class);
            Map<Gender, Map<Integer, Integer>> ends = new EnumMap<>(Gender.class);
            Map<Gender, List<Entry>> family = new EnumMap<>(Gender.class);
            for (Gender g : Gender.values()) {
                given.put(g, new HashMap<>());
                ends.put(g, new HashMap<>());
                family.put(g, new ArrayList<>());
            }
            int number = 3;
            for (String line = in.readLine(); line != null; line = in.readLine()) {
                number++;
                if (line.startsWith("#")) {
                    continue;
                }
                try {
                    row(line.split("\t", -1), given, ends, family);
                } catch (IllegalArgumentException e) {
                    throw new IllegalStateException("names " + background + " line " + number + ": " + e.getMessage(), e);
                }
            }
            Map<Gender, List<Period>> periods = new EnumMap<>(Gender.class);
            for (Gender g : Gender.values()) {
                List<Period> list = new ArrayList<>();
                given.get(g).forEach((from, weights) -> list.add(new Period(from, ends.get(g).get(from), weights,
                        weights.values().stream().mapToLong(Long::longValue).sum())));
                list.sort(Comparator.comparingInt(Period::from));
                for (int i = 1; i < list.size(); i++) {
                    if (list.get(i).from() <= list.get(i - 1).to()) {
                        throw new IllegalStateException("names " + background + ": the periods from " + list.get(i - 1).from()
                                + " and from " + list.get(i).from() + " overlap");
                    }
                }
                periods.put(g, List.copyOf(list));
            }
            if (periods.values().stream().allMatch(List::isEmpty) || family.values().stream().allMatch(List::isEmpty)) {
                throw new IllegalStateException("names " + background + ": given or family names are missing");
            }
            return new NameData(background, head.get(2), periods, family);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void row(String[] cells, Map<Gender, Map<Integer, Map<String, Long>>> given, Map<Gender, Map<Integer, Integer>> ends,
            Map<Gender, List<Entry>> family) {
        if (cells.length != 6 || cells[4].isBlank()) {
            throw new IllegalArgumentException("not six cells with a name");
        }
        long weight = Long.parseLong(cells[5]);
        if (weight < 1) {
            throw new IllegalArgumentException("a weight below 1");
        }
        if (cells[0].equals("family")) {
            // "-" for a name that both sexes carry; F or M where the language gives the name a form per sex
            for (Gender g : cells[1].equals("-") ? List.of(Gender.values()) : List.of(gender(cells[1]))) {
                family.get(g).add(new Entry(cells[4], weight));
            }
        } else if (cells[0].equals("given")) {
            Gender g = gender(cells[1]);
            int from = Integer.parseInt(cells[2]);
            int to = Integer.parseInt(cells[3]);
            Integer other = ends.get(g).putIfAbsent(from, to);
            if (from > to || other != null && other != to) {
                throw new IllegalArgumentException("a period that ends before it starts, or two ends for one start");
            }
            given.get(g).computeIfAbsent(from, k -> new java.util.LinkedHashMap<>()).merge(cells[4], weight, Long::sum);
        } else {
            throw new IllegalArgumentException("neither given nor family");
        }
    }

    private static Gender gender(String letter) {
        return switch (letter) {
            case "F" -> Gender.FEMALE;
            case "M" -> Gender.MALE;
            default -> throw new IllegalArgumentException("sex is neither F nor M");
        };
    }

    /** The period of the birth year, or the nearest one where the data has none for it. */
    private Optional<Period> period(Gender g, int birthYear) {
        return periods.get(g).stream().min(Comparator.comparingInt(p -> p.distance(birthYear)));
    }

    /** A given name of the birth period, by its weight; never one that hardly occurs then or that the other sex carries. */
    public String given(Gender g, int birthYear, RandomGenerator r) {
        Period period = period(g, birthYear).orElseThrow(
                () -> new IllegalStateException("names " + background + ": no given name for " + g));
        Table table = drawable.computeIfAbsent(period, p -> Table.of(p.weights().entrySet().stream()
                .filter(e -> cohortShare(e.getKey(), p, g) >= COHORT)
                .filter(e -> genderOf(e.getKey()).map(of -> of == g).orElse(true))
                .map(e -> new Entry(e.getKey(), e.getValue()))
                .toList()));
        if (table.total() == 0) {
            throw new IllegalStateException("names " + background + ": no given name to draw for " + g + " born " + birthYear);
        }
        return table.draw(r);
    }

    public String family(Gender g, RandomGenerator r) {
        Table table = families.get(g);
        if (table.total() == 0) {
            throw new IllegalStateException("names " + background + ": no family name for " + g);
        }
        return table.draw(r);
    }

    /** The name's share in the birth period over its largest share in any period; 0 for a name the data lacks. */
    public double cohortShare(String given, Gender g, int birthYear) {
        return period(g, birthYear).map(p -> cohortShare(given, p, g)).orElse(0.0);
    }

    private double cohortShare(String given, Period p, Gender g) {
        Double largest = largestShare.get(g).get(given);
        return largest == null ? 0.0 : p.share(given) / largest;
    }

    /** The sex that holds nine tenths or more of the name's shares, empty for a name of both or of neither. */
    public Optional<Gender> genderOf(String given) {
        double female = summedShare.get(Gender.FEMALE).getOrDefault(given, 0.0);
        double male = summedShare.get(Gender.MALE).getOrDefault(given, 0.0);
        if (female + male == 0) {
            return Optional.empty();
        }
        if (female / (female + male) >= ONE_SEX) {
            return Optional.of(Gender.FEMALE);
        }
        return male / (female + male) >= ONE_SEX ? Optional.of(Gender.MALE) : Optional.empty();
    }

    public String version() {
        return version;
    }
}
