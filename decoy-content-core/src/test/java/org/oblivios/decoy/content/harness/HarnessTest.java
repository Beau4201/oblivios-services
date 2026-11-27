// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.harness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.oblivios.decoy.content.Seed;
import org.oblivios.decoy.content.check.Checks;
import org.oblivios.decoy.content.check.ContentPlanValidator;
import org.oblivios.decoy.content.harness.PlanHarness.Config;
import org.oblivios.decoy.content.harness.PlanHarness.Report;
import org.oblivios.decoy.content.pool.PoolConversation;
import org.oblivios.decoy.content.pool.TestPool;

/**
 * The checkers over persona classes. {@link #MUST_PASS} holds the rules that report nothing; {@link #KNOWN_FAILING}
 * the rules the first 40-seed run reported. A task that fixes a rule moves it from the second set to the first.
 */
public class HarnessTest {
    public static final Set<String> MUST_PASS = union(ContentPlanValidator.RULES, Set.of("N-GENDER", "N-COHORT",
            "N-FAMILY", "N-BACKGROUND", "N-LABEL", "N-DISTINCT", "S-PHYSICAL", "O-PRESUMES", "G-SPREAD"));

    private static Set<String> union(Set<String> a, Set<String> b) {
        Set<String> out = new HashSet<>(a);
        out.addAll(b);
        return Set.copyOf(out);
    }

    /** Read from the first run on 2026-11-26: 32 plans built, 8 refused, each of these rules in at least one plan. */
    static final Set<String> KNOWN_FAILING = Set.of("C-CALLS", "C-CLOSENESS", "E-STALE", "F-PRESUMES", "G-CLOCK", "G-ORIGIN", "N-NUMBER",
            "G-SIGNATURE", "H-HARDWARE", "M-RAW", "S-ONE", "T-AWAY");
    private static final List<PoolConversation> POOL = TestPool.random(5000, Seed.of(17, 17));
    private static Report forty;

    private static synchronized Report forty() {
        if (forty == null) {
            forty = PlanHarness.run(new Config(PersonaClass.all("nl", "NL"), 40, POOL, PlanHarness.NOW, false));
        }
        return forty;
    }

    /** A rule that no set names is held by no test of this class. */
    @Test
    void everyRuleIsInExactlyOneSet() {
        Set<String> both = new HashSet<>(MUST_PASS);
        both.retainAll(KNOWN_FAILING);
        assertEquals(Set.of(), both, "in both sets");
        Set<String> listed = new HashSet<>(MUST_PASS);
        listed.addAll(KNOWN_FAILING);
        assertEquals(new TreeSet<>(Checks.ids()), new TreeSet<>(listed));
    }

    @Test
    void mustPassRulesPass() {
        Report r = forty();
        for (String id : MUST_PASS) {
            assertEquals(0, r.plansByRule().getOrDefault(id, 0), id + ": " + r.examples().get(id));
        }
    }

    @Test
    void knownFailingRulesStillFail() {
        Report r = forty();
        for (String id : KNOWN_FAILING) {
            assertTrue(r.plansByRule().getOrDefault(id, 0) > 0, id + " reports nothing: move it to MUST_PASS");
        }
    }

    @Test
    void everyClassIsBuiltOrRefusedWithAReason() {
        List<PersonaClass> eight = PersonaClass.all("nl", "NL").subList(0, 8);
        Report r = PlanHarness.run(new Config(eight, eight.size(), POOL, PlanHarness.NOW, false));
        for (PersonaClass c : eight) {
            int[] n = r.classes().get(c);
            assertNotNull(n, c.name() + " was not run");
            assertEquals(1, n[0] + n[1], c.name());
            if (n[0] == 0) {
                List<String> reasons = r.examples().get(PlanHarness.classKey(c));
                assertNotNull(reasons, c.name() + " was refused without a reason");
                assertFalse(reasons.isEmpty(), c.name());
                reasons.forEach(why -> assertTrue(why.matches("[a-zA-Z.]+: .+"), c.name() + ": the refusal names no field: " + why));
            }
        }
    }

    @Test
    void sameSeedsSameReport() {
        Config c = new Config(PersonaClass.all("nl", "NL"), 6, POOL, PlanHarness.NOW, false);
        assertEquals(PlanHarness.run(c), PlanHarness.run(c));
    }
}
