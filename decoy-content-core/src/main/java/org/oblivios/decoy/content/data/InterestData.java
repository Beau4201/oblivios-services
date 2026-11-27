// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.data;

import java.io.Reader;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.random.RandomGenerator;
import org.oblivios.decoy.content.persona.PersonaProfile.Gender;

/**
 * The sports and cultural hobbies of one country, read from {@code interests/<CC>.tsv}: per interest the percentage of
 * people who did it in twelve months, in all, per sex and per age band. The names are the source's own.
 */
public final class InterestData {
    private static final Map<String, InterestData> LOADED = new ConcurrentHashMap<>();
    /** The cells after the name: total, men, women, then the bands of ten years from 12; 70 and older is the last. */
    private static final int TOTAL = 0;
    private static final int MALE = 1;
    private static final int FEMALE = 2;
    private static final int FIRST_AGES = 3;
    private static final int FIGURES = 10;

    private final Map<String, double[]> figures = new LinkedHashMap<>();
    private final String version;

    private InterestData(String country, Reader reader) {
        String file = "interests/" + country + ".tsv";
        version = Tables.read(file, reader, 1 + FIGURES, c -> {
            double[] row = new double[FIGURES];
            for (int i = 0; i < FIGURES; i++) {
                row[i] = Double.parseDouble(c[i + 1]);
                if (!(row[i] >= 0 && row[i] <= 100)) {
                    throw new IllegalArgumentException("not a percentage: " + c[i + 1]);
                }
            }
            if (figures.put(c[0], row) != null) {
                throw new IllegalArgumentException("the interest " + c[0] + " twice");
            }
        });
    }

    /** Throws {@link IllegalArgumentException} for a country without the file: no other country's stands in. */
    public static InterestData load(String country) {
        if (!Tables.has(country, "interests", "{cc}.tsv")) {
            throw new IllegalArgumentException("no interests for country " + country);
        }
        return LOADED.computeIfAbsent(country, c -> new InterestData(c, Tables.open("interests/" + c + ".tsv")));
    }

    static InterestData parse(String country, Reader reader) {
        return new InterestData(country, reader);
    }

    public Set<String> names() {
        return Set.copyOf(figures.keySet());
    }

    /**
     * The chance of one interest at this age and sex. The source crosses no sex with an age, so the share of the age is
     * moved as the sex moves the country's share, at most to 1, {@code [assumption]}.
     */
    public double share(String name, int age, Gender g) {
        double[] row = figures.get(name);
        if (row == null) {
            throw new IllegalArgumentException("no interest " + name);
        }
        if (row[TOTAL] == 0) {
            return 0;
        }
        int ages = FIRST_AGES + Math.max(0, Math.min(FIGURES - FIRST_AGES - 1, age < 20 ? 0 : age / 10 - 1));
        return Math.min(1.0, row[ages] * row[g == Gender.MALE ? MALE : FEMALE] / row[TOTAL] / 100);
    }

    /** Each interest by its own chance, one apart from the other, {@code [assumption]}: no source gives pairs. */
    public Set<String> draw(int age, Gender g, RandomGenerator r) {
        Set<String> out = new LinkedHashSet<>();
        for (String name : figures.keySet()) {
            if (r.nextDouble() < share(name, age, g)) {
                out.add(name);
            }
        }
        return out;
    }

    public String version() {
        return version;
    }
}
