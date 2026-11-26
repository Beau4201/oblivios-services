// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.check;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Acceptance 4 of the life story design: every rule has a planted case, and the case goes unreported with the rule off. */
class RuleFixturesTest {
    private static boolean reports(List<Violation> found, String id) {
        return found.stream().anyMatch(v -> v.ruleId().equals(id));
    }

    @Test
    void everyRuleHasAPlantedCase() {
        assertEquals(Checks.ids(), RuleFixtures.ids());
    }

    @Test
    void everyPlantedCaseIsReported() {
        for (String id : RuleFixtures.ids()) {
            List<Violation> found = RuleFixtures.of(id).report(Set.of());
            assertTrue(reports(found, id), id + " is not reported on its planted case: " + found);
        }
    }

    @Test
    void everyRuleSwitchedOffIsMissed() {
        for (String id : RuleFixtures.ids()) {
            List<Violation> found = RuleFixtures.of(id).report(Set.of(id));
            assertFalse(reports(found, id), id + " is reported while it is off");
        }
    }
}
