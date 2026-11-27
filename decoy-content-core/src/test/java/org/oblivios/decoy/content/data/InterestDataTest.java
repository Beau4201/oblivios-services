// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringReader;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.oblivios.decoy.content.Seed;
import org.oblivios.decoy.content.country.Packs;
import org.oblivios.decoy.content.persona.PersonaProfile.Gender;

class InterestDataTest {
    private static final String HEAD = "# source: s\n# licence: l\n# version: v\n";

    private static InterestData parse(String rows) {
        return InterestData.parse("NL", new StringReader(HEAD + rows));
    }

    /** One topic: total, men, women, then 12-19 to 70 and older. */
    private static String row(String name, String total, String male, String female, String young, String old) {
        return name + "\t" + total + "\t" + male + "\t" + female + "\t" + young
                + "\t50.0\t50.0\t50.0\t50.0\t50.0\t" + old + "\n";
    }

    @Test
    void theShareIsTheAgeShareMovedByTheSex() {
        InterestData data = parse(row("a", "20.0", "30.0", "10.0", "40.0", "4.0"));
        assertEquals(0.6, data.share("a", 16, Gender.MALE), 1e-9);
        assertEquals(0.2, data.share("a", 19, Gender.FEMALE), 1e-9);
        assertEquals(0.06, data.share("a", 90, Gender.MALE), 1e-9);
    }

    @Test
    void aShareNeverPassesOne() {
        InterestData data = parse(row("a", "50.0", "90.0", "10.0", "80.0", "4.0"));
        assertEquals(1.0, data.share("a", 16, Gender.MALE), 1e-9);
    }

    @Test
    void anInterestNobodyOfTheCountryHasIsNeverDrawn() {
        InterestData data = parse(row("a", "0.0", "0.0", "0.0", "0.0", "0.0"));
        assertEquals(Set.of(), data.draw(16, Gender.MALE, Seed.of(1, 2).stream("x")));
    }

    @Test
    void theDrawFollowsTheShares() {
        InterestData data = DataSet.of("NL").interests();
        int plans = 20_000;
        int found = 0;
        for (int n = 0; n < plans; n++) {
            found += data.draw(25, Gender.MALE, Seed.of(n, 31L * n + 1).stream("persona.interests")).contains("Fitness") ? 1 : 0;
        }
        assertEquals(data.share("Fitness", 25, Gender.MALE), found / (double) plans, 0.015);
        assertTrue(data.share("Fitness", 25, Gender.MALE) > data.share("Fitness", 75, Gender.MALE));
    }

    @Test
    void theFileOfTheNetherlandsHoldsSportsAndCulturalHobbies() {
        InterestData data = DataSet.of("NL").interests();
        assertTrue(data.names().contains("Fitness") && data.names().contains("Gamen"), data.names().toString());
        assertTrue(data.names().stream().noneMatch(n -> n.startsWith("Geen ") || n.startsWith("Andere ")), data.names().toString());
    }

    @Test
    void theVersionIsRecorded() {
        DataSet set = DataSet.of("NL");
        set.interests();
        assertTrue(set.versions().containsKey("interests/NL"), set.versions().toString());
    }

    @Test
    void aCountryWithoutTheFileIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> InterestData.load(Packs.neutral().code()));
    }

    @Test
    void aBrokenRowIsRefused() {
        assertThrows(IllegalStateException.class, () -> parse("a\t1.0\n"));
        assertThrows(IllegalStateException.class, () -> parse(row("a", "x", "1", "1", "1", "1")));
        assertThrows(IllegalStateException.class,
                () -> parse(row("a", "1", "1", "1", "1", "1") + row("a", "1", "1", "1", "1", "1")));
    }
}
