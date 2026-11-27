// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;
import org.oblivios.decoy.content.persona.PersonaProfile.Gender;
import org.oblivios.decoy.content.persona.PersonaProfile.Housing;
import org.oblivios.decoy.content.persona.PersonaProfile.JobKind;
import org.oblivios.decoy.content.persona.PersonaProfile.Pet;
import org.oblivios.decoy.content.persona.PersonaProfile.Relationship;
import org.oblivios.decoy.content.persona.PersonaProfile.Transport;

class SpreadsTest {
    private static final Spreads NL = Spreads.load("NL");

    private static double share(int[] weights, Enum<?> kind) {
        int total = 0;
        for (int w : weights) {
            total += w;
        }
        return weights[kind.ordinal()] / (double) total;
    }

    @Test
    void agesFollowTheAgeSpread() {
        SplittableRandom r = new SplittableRandom(1);
        int[] byBand = new int[11];
        int draws = 55_000;
        for (int i = 0; i < draws; i++) {
            int age = NL.age(16, 69, r);
            assertTrue(age >= 16 && age <= 69, "age " + age);
            byBand[(age - 15) / 5]++;
        }
        double[] want = new double[11];
        double sum = 0;
        for (int b = 0; b < 11; b++) {
            int from = 15 + 5 * b;
            // of the first band only the years from 16 on can be drawn
            want[b] = NL.value("age." + from + "-" + (from + 4)) * (b == 0 ? 0.8 : 1.0);
            sum += want[b];
        }
        for (int b = 0; b < 11; b++) {
            assertEquals(want[b] / sum, byBand[b] / (double) draws, 0.03, "band " + b);
        }
    }

    @Test
    void anAgeAboveTheLastBandCanStillBeDrawn() {
        SplittableRandom r = new SplittableRandom(2);
        for (int i = 0; i < 100; i++) {
            int age = NL.age(90, 100, r);
            assertTrue(age >= 90 && age <= 100);
        }
        assertThrows(IllegalArgumentException.class, () -> NL.age(50, 40, r));
    }

    @Test
    void jobFollowsAgeAndSex() {
        assertEquals(1.0, share(NL.jobWeights(70, Gender.MALE), JobKind.RETIRED));
        assertTrue(share(NL.jobWeights(16, Gender.FEMALE), JobKind.PUPIL) > 0.8);
        for (Gender g : Gender.values()) {
            String at = "labour." + (g == Gender.MALE ? "M" : "F") + ".40-44.";
            double self = NL.value(at + "selfEmployed") / NL.value(at + "total");
            assertEquals(self, share(NL.jobWeights(42, g), JobKind.SELF_EMPLOYED), 0.002);
            assertEquals(NL.value(at + "unemployed") / NL.value(at + "total"),
                    share(NL.jobWeights(42, g), JobKind.JOB_SEEKER), 0.002);
            assertEquals(0.0, share(NL.jobWeights(42, g), JobKind.PUPIL));
            assertEquals(0.0, share(NL.jobWeights(42, g), JobKind.RETIRED));
        }
        assertTrue(share(NL.jobWeights(63, Gender.MALE), JobKind.RETIRED) > 0.0);
    }

    @Test
    void householdsGiveRelationshipHousingAndChildren() {
        String at = "household.F.40-44.";
        double total = NL.value(at + "total");
        assertEquals(NL.value(at + "married") / total,
                share(NL.relationshipWeights(42, Gender.FEMALE), Relationship.MARRIED), 0.002);
        assertEquals(NL.value(at + "child") / total,
                share(NL.housingWeights(42, Gender.FEMALE), Housing.WITH_PARENTS), 0.002);
        assertEquals(100 * NL.value(at + "withChildren") / total, NL.childPercent(42, Gender.FEMALE), 0.5);
        assertEquals((int) NL.value("children.percent.from45"), NL.childPercent(60, Gender.FEMALE));
        assertTrue(NL.partnerAtHomePercent(42, Gender.FEMALE) > 50 && NL.partnerAtHomePercent(42, Gender.FEMALE) < 100);
    }

    @Test
    void aBandNeverGivesAnAgeWhatThatAgeCannotHave() {
        for (Gender g : Gender.values()) {
            for (int age = 16; age < 18; age++) {
                assertEquals(0.0, share(NL.relationshipWeights(age, g), Relationship.MARRIED), "married at " + age);
                assertEquals(0.0, share(NL.housingWeights(age, g), Housing.OWN), "an owner at " + age);
            }
            for (int age : new int[] {70, 90, 100}) {
                assertEquals(0.0, share(NL.housingWeights(age, g), Housing.WITH_PARENTS), "with parents at " + age);
            }
        }
    }

    @Test
    void noPersonaUnderEighteenDrivesACar() {
        assertEquals(0.0, share(NL.transportWeights(17), Transport.CAR));
        assertTrue(share(NL.transportWeights(45), Transport.CAR) > share(NL.transportWeights(20), Transport.CAR));
        assertTrue(share(NL.transportWeights(20), Transport.BIKE) > 0.0);
    }

    @Test
    void petsFollowTheSurvey() {
        assertEquals(16, NL.petPercent(Pet.DOG));
        assertEquals(23, NL.petPercent(Pet.CAT));
        assertEquals(10, NL.petPercent(Pet.OTHER));
    }

    @Test
    void everyKeyHasASource() {
        assertTrue(NL.keys().size() > 200, "keys " + NL.keys().size());
        for (String key : NL.keys()) {
            assertTrue(!NL.sourceOf(key).isBlank(), key);
        }
        assertEquals("[assumption]", NL.sourceOf("job.shift.percent"));
        assertTrue(NL.sourceOf("age.40-44").startsWith("CBS table 85458NED"), NL.sourceOf("age.40-44"));
        assertThrows(IllegalStateException.class,
                () -> Spreads.parse("ZZ", "# source: x\n# licence: x\n# version: x\nversion=x\n\na=1\n"));
        assertThrows(IllegalArgumentException.class, () -> NL.value("no.such.key"));
    }

    @Test
    void aHabitRangeIsDrawnWithin() {
        Spreads s = Spreads.parse("ZZ", "# source: h\n# licence: l\n# version: v\n# source: [assumption]\n"
                + "calls.low=1\ncalls.high=3\nversion=v\n");
        SplittableRandom r = new SplittableRandom(3);
        double least = 9;
        double most = 0;
        for (int i = 0; i < 500; i++) {
            double v = s.draw("calls", r);
            assertTrue(v >= 1 && v <= 3, "drawn " + v);
            least = Math.min(least, v);
            most = Math.max(most, v);
        }
        assertTrue(least < 1.2 && most > 2.8, least + " to " + most);
    }

    @Test
    void aCountryWithoutSpreadsIsRefused() {
        for (String country : new String[] {"DE", "XX", "../NL", null}) {
            assertThrows(IllegalArgumentException.class, () -> Spreads.load(country), String.valueOf(country));
        }
    }
}
