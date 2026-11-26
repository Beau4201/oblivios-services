// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.data;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.HashSet;
import java.util.Set;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;
import org.oblivios.decoy.content.country.Packs;

class FamilyRulesTest {
    private static FamilyRules nl() {
        return FamilyRules.of(Packs.load("NL").get());
    }

    @Test
    void siblingsShareTheFamilyName() {
        for (int couple = 0; couple < 200; couple++) {
            FamilyRules rules = nl();
            String first = rules.childFamily("Fa" + couple, "Mo" + couple, new SplittableRandom(couple));
            for (int sibling = 1; sibling < 5; sibling++) {
                assertEquals(first, rules.childFamily("Fa" + couple, "Mo" + couple, new SplittableRandom(1_000L * sibling + couple)));
            }
        }
    }

    @Test
    void childTakesOneParentsNameOrBoth() {
        Set<String> seen = new HashSet<>();
        SplittableRandom r = new SplittableRandom(5);
        for (int i = 0; i < 2_000; i++) {
            seen.add(nl().childFamily("Fa", "Mo", r));
        }
        assertEquals(Set.of("Fa", "Mo", "Fa Mo", "Mo Fa"), seen);
    }

    @Test
    void aParentWithoutAKnownNameLeavesTheOther() {
        SplittableRandom r = new SplittableRandom(5);
        for (int i = 0; i < 100; i++) {
            assertEquals("Mo", nl().childFamily("", "Mo", r));
            assertEquals("Fa", nl().childFamily("Fa", "", r));
        }
    }

    @Test
    void marriedPartnerMayUseTheOther() {
        Set<String> married = new HashSet<>();
        SplittableRandom r = new SplittableRandom(9);
        for (int i = 0; i < 2_000; i++) {
            married.add(nl().usedFamily("Own", "Their", true, r));
            assertEquals("Own", nl().usedFamily("Own", "Their", false, r));
        }
        assertEquals(Set.of("Own", "Their", "Their-Own", "Own-Their"), married);
    }
}
