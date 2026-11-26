// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.StringReader;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;
import org.oblivios.decoy.content.Seed;
import org.oblivios.decoy.content.TestSetup;
import org.oblivios.decoy.content.harness.PlanHarness;
import org.oblivios.decoy.content.persona.PersonaProfile;
import org.oblivios.decoy.content.persona.PersonaProfile.Gender;

class PopulationDataTest {
    private static final String UTRECHT = "GM0344";

    /** The shares by origin of one band of Utrecht, computed here from the file; both sexes for {@code sex} null. */
    private static Map<String, Double> shares(String band, String sex) throws IOException {
        Map<String, Double> out = new HashMap<>();
        for (String[] c : PlaceDataTest.rows("population/NL.tsv.gz")) {
            if (c[0].equals(UTRECHT) && c[1].equals(band) && (sex == null || c[2].equals(sex))) {
                out.merge(c[3], Double.parseDouble(c[4]), Double::sum);
            }
        }
        double total = out.values().stream().mapToDouble(Double::doubleValue).sum();
        out.replaceAll((origin, n) -> n / total);
        return out;
    }

    @Test
    void drawsFollowTheShares() throws IOException {
        Map<String, Double> expected = shares("35-39", "F");
        PopulationData nl = PopulationData.load("NL");
        SplittableRandom r = new SplittableRandom(38);
        Map<String, Integer> drawn = new HashMap<>();
        for (int i = 0; i < 10_000; i++) {
            drawn.merge(nl.background(UTRECHT, 38, Gender.FEMALE, r), 1, Integer::sum);
        }
        assertTrue(expected.size() > 5 && expected.keySet().containsAll(drawn.keySet()), drawn.toString());
        expected.forEach((origin, share) ->
                assertEquals(share, drawn.getOrDefault(origin, 0) / 10_000.0, 0.02, origin));
    }

    @Test
    void theShareOfABackgroundCountsBothSexes() throws IOException {
        assertEquals(shares("35-39", null).get("TR"), PopulationData.load("NL").share(UTRECHT, 38, "TR"), 1e-9);
        assertEquals(0.0, PopulationData.load("NL").share(UTRECHT, 38, "ZZ"), 0.0);
    }

    @Test
    void aDrawAmongSomeBackgroundsNeverLeavesThem() {
        PopulationData nl = PopulationData.load("NL");
        SplittableRandom r = new SplittableRandom(39);
        for (int i = 0; i < 500; i++) {
            assertTrue(Set.of("TR", "MA").contains(nl.background(UTRECHT, 38, Gender.MALE, Set.of("TR", "MA"), r)));
        }
        assertThrows(IllegalArgumentException.class,
                () -> nl.background(UTRECHT, 38, Gender.MALE, Set.of("ZZ"), r));
    }

    @Test
    void anEmptyAgeCellTakesEveryAgeOfThatSex() {
        PopulationData small = PopulationData.parse("XY", new StringReader(
                "# source: s\n# licence: l\n# version: v\nGM1\t0-4\tF\tNL\t5\nGM1\t100+\tM\tTR\t5\n"));
        assertEquals("NL", small.background("GM1", 100, Gender.FEMALE, new SplittableRandom(1)));
        assertThrows(IllegalArgumentException.class,
                () -> small.background("GM2", 30, Gender.FEMALE, new SplittableRandom(1)));
    }

    @Test
    void openBackgroundIsDrawnFromTheCity() {
        DataSet data = DataSet.of("NL");
        for (long n = 0; n < 50; n++) {
            String background = TestSetup.derive(Seed.of(n, 4),
                    PersonaProfile.open(PlanHarness.inputs("nl", "NL")), 2026, data).background();
            assertTrue(NameData.backgrounds().contains(background), background);
        }
        assertEquals(PopulationData.load("NL").version(), data.versions().get("population/NL"));
    }

    @Test
    void aCountryWithoutPopulationDataIsRefused() {
        assertEquals("no population data for country XX",
                assertThrows(IllegalArgumentException.class, () -> PopulationData.load("XX")).getMessage());
    }
}
