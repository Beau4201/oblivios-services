// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.harness;

import java.math.BigInteger;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;
import org.oblivios.decoy.content.PersonaInputs;
import org.oblivios.decoy.content.TestSetup;
import org.oblivios.decoy.content.persona.PersonaProfile;
import org.oblivios.decoy.content.persona.PersonaProfile.Housing;
import org.oblivios.decoy.content.persona.PersonaProfile.JobKind;
import org.oblivios.decoy.content.persona.PersonaProfile.Relationship;
import org.oblivios.decoy.content.persona.PersonaProfile.TravelMode;
import org.oblivios.decoy.content.persona.TripAnswer;
import org.oblivios.decoy.content.pool.AgeBand;

/**
 * A class of persona the harness covers: the setup answers that shape a life most, given, and the birth year and the
 * children's ages drawn inside their bands.
 */
public record PersonaClass(JobKind job, Relationship relationship, ChildBand children, Housing housing, AgeBand age, String language,
        String country) {

    /** The ages of the children: none, under 4, 4 to 12, 13 to 19. */
    public enum ChildBand {
        NONE(-1, -1), YOUNG(0, 3), SCHOOL(4, 12), TEEN(13, 19);

        final int from;
        final int to;

        ChildBand(int from, int to) {
            this.from = from;
            this.to = to;
        }
    }

    public static final List<AgeBand> AGES = List.of(new AgeBand(16, 24), new AgeBand(25, 39), new AgeBand(40, 59), new AgeBand(60, 80));
    /** A stride coprime to the number of classes, so that the first seeds of a run already reach every field. */
    static final int STRIDE = 1093;

    /** Every combination of the fields, in an order that spreads consecutive classes over all of them. */
    public static List<PersonaClass> all(String language, String country) {
        List<PersonaClass> grid = new ArrayList<>();
        for (JobKind job : JobKind.values()) {
            for (Relationship rel : Relationship.values()) {
                for (ChildBand children : ChildBand.values()) {
                    for (Housing housing : Housing.values()) {
                        for (AgeBand age : AGES) {
                            grid.add(new PersonaClass(job, rel, children, housing, age, language, country));
                        }
                    }
                }
            }
        }
        int n = grid.size();
        if (!BigInteger.valueOf(STRIDE).gcd(BigInteger.valueOf(n)).equals(BigInteger.ONE)) {
            throw new IllegalStateException("the stride " + STRIDE + " shares a factor with " + n + " classes");
        }
        List<PersonaClass> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(grid.get((int) ((long) i * STRIDE % n)));
        }
        return List.copyOf(out);
    }

    /**
     * The profile of this class for the inputs: its fields given, the birth year and 1 or 2 children drawn in the bands,
     * and one trip of the year, because the rules about days away judge nothing without one.
     */
    public PersonaProfile profile(PersonaInputs in, RandomGenerator r, int year) {
        int birthYear = year - (age.from() + r.nextInt(age.to() - age.from() + 1));
        List<Integer> kids = new ArrayList<>();
        if (children != ChildBand.NONE) {
            int count = 1 + r.nextInt(2);
            for (int i = 0; i < count; i++) {
                kids.add(children.from + r.nextInt(children.to - children.from + 1));
            }
            kids.sort(null);
        }
        // far enough to fly, near enough to drive in the fewest days drawn; a month that every run's year has behind it
        boolean flies = r.nextBoolean();
        TripAnswer trip = new TripAnswer(YearMonth.of(year, 1 + r.nextInt(10)), 3 + r.nextInt(12), flies ? "ES" : "DE",
                flies ? TravelMode.PLANE : TravelMode.CAR);
        return PersonaProfile.builder(in).job(job).relationship(relationship).housing(housing)
                .children(TestSetup.aged(kids.toArray(Integer[]::new))).birthYear(birthYear).languages(List.of(language))
                .trips(List.of(trip)).build();
    }

    /** The class as one word, as the report writes it. */
    public String name() {
        return job + "/" + relationship + "/" + children + "/" + housing + "/" + age.from() + "-" + age.to() + "/" + language + "/" + country;
    }
}
