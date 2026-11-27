// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.data;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.random.RandomGenerator;
import org.oblivios.decoy.content.persona.PersonaProfile.Gender;
import org.oblivios.decoy.content.persona.PersonaProfile.Housing;
import org.oblivios.decoy.content.persona.PersonaProfile.JobKind;
import org.oblivios.decoy.content.persona.PersonaProfile.Pet;
import org.oblivios.decoy.content.persona.PersonaProfile.Relationship;
import org.oblivios.decoy.content.persona.PersonaProfile.Transport;

/**
 * The figures a persona's open answers are drawn from, read from {@code spreads/<CC>.properties}. Every key stands
 * under a {@code # source:} line; what no source gives is marked {@code [assumption]} there. Weights come in the order
 * of their enum and in parts of {@value #PARTS}; the constraints of a profile are the caller's.
 */
public final class Spreads {
    static final int PARTS = 100_000;
    private static final String SOURCE = "# source: ";
    private static final Map<String, Spreads> LOADED = new ConcurrentHashMap<>();
    /** The bands of the household and labour figures; an age outside takes the nearest one. */
    private static final int FIRST_BAND = 15;
    private static final int LAST_BAND = 65;
    private static final int LAST_AGE_BAND = 70;

    private final String country;
    private final Map<String, String> values = new LinkedHashMap<>();
    private final Map<String, String> sources = new LinkedHashMap<>();
    private final String version;

    private Spreads(String country, Reader reader) {
        this.country = country;
        try (BufferedReader in = new BufferedReader(reader)) {
            for (String start : List.of(SOURCE, "# licence: ", "# version: ")) {
                String line = in.readLine();
                if (line == null || !line.startsWith(start)) {
                    throw broken("the line '" + start + "...' is missing");
                }
            }
            String source = null;
            for (String line = in.readLine(); line != null; line = in.readLine()) {
                if (line.startsWith(SOURCE)) {
                    source = line.substring(SOURCE.length()).trim();
                } else if (!line.startsWith("#")) {
                    int at = line.indexOf('=');
                    if (at < 1) {
                        throw broken("not a key and a value: '" + line + "'");
                    }
                    String key = line.substring(0, at);
                    if (!key.equals("version") && (source == null || source.isEmpty())) {
                        throw broken("the key " + key + " has no source");
                    }
                    if (values.put(key, line.substring(at + 1)) != null) {
                        throw broken("the key " + key + " twice");
                    }
                    if (!key.equals("version")) {
                        sources.put(key, source);
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        version = values.remove("version");
        if (version == null) {
            throw broken("no version");
        }
    }

    private IllegalStateException broken(String what) {
        return new IllegalStateException("spreads/" + country + ": " + what);
    }

    /** Throws {@link IllegalArgumentException} for a country without spreads: no other country's stand in. */
    public static Spreads load(String country) {
        if (!Tables.has(country, "spreads", "{cc}.properties")) {
            throw new IllegalArgumentException("no spreads for country " + country);
        }
        return LOADED.computeIfAbsent(country, c -> new Spreads(c, Tables.open("spreads/" + c + ".properties")));
    }

    static Spreads parse(String country, String text) {
        return new Spreads(country, new StringReader(text));
    }

    public double value(String key) {
        String found = values.get(key);
        if (found == null) {
            throw new IllegalArgumentException("spreads/" + country + " has no " + key);
        }
        return Double.parseDouble(found);
    }

    public String sourceOf(String key) {
        value(key);
        return sources.get(key);
    }

    Set<String> keys() {
        return sources.keySet();
    }

    /** A persona's own value of a habit, uniform between {@code <key>.low} and {@code <key>.high}. */
    public double draw(String key, RandomGenerator r) {
        double low = value(key + ".low");
        return low + r.nextDouble() * (value(key + ".high") - low);
    }

    /** An age from {@code least} to {@code most}, each year weighted by the residents of its band of five years. */
    public int age(int least, int most, RandomGenerator r) {
        if (least > most) {
            throw new IllegalArgumentException("no age from " + least + " to " + most);
        }
        double[] upTo = new double[most - least + 1];
        double sum = 0;
        for (int i = 0; i < upTo.length; i++) {
            // above the last band the file has no figure: each year counts as one of that band, [assumption]
            sum += value("age." + band(least + i, FIRST_BAND, LAST_AGE_BAND));
            upTo[i] = sum;
        }
        double roll = r.nextDouble() * sum;
        int i = 0;
        while (i < upTo.length - 1 && roll >= upTo[i]) {
            i++;
        }
        return least + i;
    }

    private static String band(int age, int first, int last) {
        int from = Math.max(first, Math.min(last, age / 5 * 5));
        return from + "-" + (from + 4);
    }

    private double of(String table, Gender g, int age, String name) {
        return value(table + "." + (g == Gender.MALE ? "M" : "F") + "." + band(age, FIRST_BAND, LAST_BAND) + "." + name);
    }

    private static int parts(double share) {
        return (int) Math.round(Math.max(0, share) * PARTS);
    }

    private double percent(String key) {
        return value(key) / 100.0;
    }

    public int[] jobWeights(int age, Gender g) {
        int[] w = new int[JobKind.values().length];
        if (age < 19) {
            w[JobKind.PUPIL.ordinal()] = parts(percent("job.pupil.percent"));
            w[JobKind.SHIFT.ordinal()] = PARTS - w[JobKind.PUPIL.ordinal()];
        } else if (age <= 24) {
            for (JobKind k : List.of(JobKind.STUDENT, JobKind.SHIFT, JobKind.OFFICE, JobKind.JOB_SEEKER)) {
                w[k.ordinal()] = parts(percent("job.19-24." + k));
            }
        } else if (age >= value("job.pension.age")) {
            w[JobKind.RETIRED.ordinal()] = PARTS;
        } else {
            double total = of("labour", g, age, "total");
            double employed = of("labour", g, age, "employed") / total;
            double self = of("labour", g, age, "selfEmployed") / total;
            double seeking = of("labour", g, age, "unemployed") / total;
            double employees = employed - self;
            double shift = employees * percent("job.shift.percent");
            double inactive = Math.max(0, 1 - employed - seeking);
            double retired = age >= 60 ? inactive * percent("job.inactive.retired.percent.from60") : 0;
            double student = age <= 35 ? inactive * percent("job.inactive.student.percent.to35") : 0;
            double carer = (inactive - retired - student) * percent("job.inactive.carer.percent");
            w[JobKind.OFFICE.ordinal()] = parts(employees - shift);
            w[JobKind.SHIFT.ordinal()] = parts(shift);
            w[JobKind.SELF_EMPLOYED.ordinal()] = parts(self);
            w[JobKind.JOB_SEEKER.ordinal()] = parts(seeking);
            w[JobKind.RETIRED.ordinal()] = parts(retired);
            w[JobKind.STUDENT.ordinal()] = parts(student);
            w[JobKind.CARER.ordinal()] = parts(carer);
            w[JobKind.NONE.ordinal()] = parts(inactive - retired - student - carer);
        }
        return w;
    }

    /** The shares of those who share no home with a partner: with a partner elsewhere, divorced, and the rest. */
    private double[] apart(int age, Gender g) {
        double total = of("household", g, age, "total");
        double alone = Math.max(0, 1 - (of("household", g, age, "married") + of("household", g, age, "partner")) / total);
        double partner = alone * percent("relationship.apart.percent");
        String divorcedKey = "relationship.divorced.percent." + (age < 30 ? "to29" : age < 50 ? "to49" : "from50");
        double divorced = (alone - partner) * percent(divorcedKey);
        return new double[] {partner, divorced, alone - partner - divorced};
    }

    public int[] relationshipWeights(int age, Gender g) {
        double total = of("household", g, age, "total");
        double[] apart = apart(age, g);
        int[] w = new int[Relationship.values().length];
        // the band of 15 to 19 holds married people of 18 and 19; nobody younger marries in the Netherlands, [assumption]
        w[Relationship.MARRIED.ordinal()] = age < 18 ? 0 : parts(of("household", g, age, "married") / total);
        w[Relationship.PARTNER.ordinal()] = parts(of("household", g, age, "partner") / total + apart[0]);
        w[Relationship.DIVORCED.ordinal()] = parts(apart[1]);
        w[Relationship.SINGLE.ordinal()] = parts(apart[2]);
        return w;
    }

    /** Of the unmarried personas with a partner, the share who share the home. */
    public int partnerAtHomePercent(int age, Gender g) {
        double together = of("household", g, age, "partner") / of("household", g, age, "total");
        double all = together + apart(age, g)[0];
        return all == 0 ? 0 : (int) Math.round(100 * together / all);
    }

    public int[] housingWeights(int age, Gender g) {
        double total = of("household", g, age, "total");
        // from 70 on no figure is read, and the last band's must not give a persona parents to live with, [assumption]
        double parents = age >= LAST_AGE_BAND ? 0 : of("household", g, age, "child") / total;
        // "other member of the household" is the nearest the figures come to a shared house, [assumption]
        double sharing = of("household", g, age, "other") / total;
        double own = age < 18 ? 0
                : (1 - parents - sharing) * percent("housing.own.percent." + (age < 30 ? "to29" : "from30"));
        int[] w = new int[Housing.values().length];
        w[Housing.WITH_PARENTS.ordinal()] = parts(parents);
        w[Housing.HOUSE_SHARE.ordinal()] = parts(sharing);
        w[Housing.OWN.ordinal()] = parts(own);
        w[Housing.RENT.ordinal()] = parts(1 - parents - sharing - own);
        return w;
    }

    /** The chance that a persona of this age has children; up to 44, as many as have a child at home. */
    public int childPercent(int age, Gender g) {
        if (age >= 45) {
            return (int) value("children.percent.from45");
        }
        return (int) Math.round(100 * of("household", g, age, "withChildren") / of("household", g, age, "total"));
    }

    /** The main means of transport, by the trips of the age; nobody under 18 drives. */
    public int[] transportWeights(int age) {
        String ages = age < 18 ? "12-17" : age < 25 ? "18-24" : age < 35 ? "25-34" : age < 50 ? "35-49"
                : age < 65 ? "50-64" : age < 75 ? "65-74" : "75+";
        int[] w = new int[Transport.values().length];
        for (Transport t : Transport.values()) {
            w[t.ordinal()] = t == Transport.CAR && age < 18 ? 0 : (int) Math.round(100 * value("trips." + ages + "." + t));
        }
        return w;
    }

    public int petPercent(Pet pet) {
        return (int) value("pet." + pet + ".percent");
    }

    public String version() {
        return version;
    }
}
