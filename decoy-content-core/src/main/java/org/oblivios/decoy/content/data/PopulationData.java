// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.data;

import java.io.Reader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.random.RandomGenerator;
import org.oblivios.decoy.content.persona.PersonaProfile.Gender;

/**
 * The residents of each municipality by age band, sex and origin, from {@code population/<CC>.tsv.gz}. An origin is a
 * country code; {@code XX} stands for every origin the file does not name.
 */
public final class PopulationData {
    private static final Map<String, PopulationData> LOADED = new ConcurrentHashMap<>();

    private record Band(int from, int to) {
        boolean holds(int age) {
            return age >= from && age <= to;
        }

        static Band of(String text) {
            if (text.endsWith("+")) {
                return new Band(Integer.parseInt(text.substring(0, text.length() - 1)), Integer.MAX_VALUE);
            }
            int dash = text.indexOf('-');
            if (dash < 0) {
                throw new IllegalArgumentException("not an age band: " + text);
            }
            return new Band(Integer.parseInt(text.substring(0, dash)), Integer.parseInt(text.substring(dash + 1)));
        }
    }

    private record Cell(Band band, Gender gender, String origin, long residents) {}

    private final Map<String, List<Cell>> cells;
    private final String version;

    private PopulationData(Map<String, List<Cell>> cells, String version) {
        this.cells = cells;
        this.version = version;
    }

    /** Throws {@link IllegalArgumentException} for a country without population data. */
    public static PopulationData load(String country) {
        if (!Tables.has(country, "population", "{cc}.tsv.gz")) {
            throw new IllegalArgumentException("no population data for country " + country);
        }
        return LOADED.computeIfAbsent(country, c -> parse(c, Tables.open("population/" + c + ".tsv.gz")));
    }

    static PopulationData parse(String country, Reader reader) {
        Map<String, List<Cell>> cells = new HashMap<>();
        String version = Tables.read("population/" + country, reader, 5, c -> {
            Gender gender = switch (c[2]) {
                case "F" -> Gender.FEMALE;
                case "M" -> Gender.MALE;
                default -> throw new IllegalArgumentException("sex is neither F nor M");
            };
            cells.computeIfAbsent(c[0], k -> new ArrayList<>())
                    .add(new Cell(Band.of(c[1]), gender, c[3], Long.parseLong(c[4])));
        });
        return new PopulationData(cells, version);
    }

    /** The residents by origin of the cells that pass, in the order of the origins. */
    private Map<String, Long> count(String municipality, Predicate<Cell> which) {
        Map<String, Long> out = new TreeMap<>();
        for (Cell c : cells.getOrDefault(municipality, List.of())) {
            if (which.test(c)) {
                out.merge(c.origin(), c.residents(), Long::sum);
            }
        }
        return out;
    }

    /** An origin drawn by the residents of this age and sex; {@code XX} among them. */
    public String background(String municipality, int age, Gender g, RandomGenerator r) {
        return background(municipality, age, g, null, r);
    }

    /**
     * As {@link #background(String, int, Gender, RandomGenerator)}, but only among {@code among} (any for
     * {@code null}). Where no such resident has this age, which small municipalities have for the oldest, every age of
     * that sex counts. Throws {@link IllegalArgumentException} when the municipality holds none of them at all.
     */
    public String background(String municipality, int age, Gender g, Set<String> among, RandomGenerator r) {
        Predicate<Cell> wanted = c -> c.gender() == g && (among == null || among.contains(c.origin()));
        Map<String, Long> found = count(municipality, wanted.and(c -> c.band().holds(age)));
        if (found.isEmpty()) {
            found = count(municipality, wanted);
        }
        long total = found.values().stream().mapToLong(Long::longValue).sum();
        if (total == 0) {
            throw new IllegalArgumentException("no population of these backgrounds in municipality " + municipality);
        }
        long pick = r.nextLong(total);
        for (Map.Entry<String, Long> e : found.entrySet()) {
            pick -= e.getValue();
            if (pick < 0) {
                return e.getKey();
            }
        }
        throw new IllegalStateException("unreachable: the pick is below the total");
    }

    /** The share of an origin among the residents of this age, both sexes; 0 where nobody has this age. */
    public double share(String municipality, int age, String background) {
        Map<String, Long> found = count(municipality, c -> c.band().holds(age));
        long total = found.values().stream().mapToLong(Long::longValue).sum();
        return total == 0 ? 0.0 : found.getOrDefault(background, 0L) / (double) total;
    }

    public String version() {
        return version;
    }
}
