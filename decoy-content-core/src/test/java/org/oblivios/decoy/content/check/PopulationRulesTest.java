// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.check;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.oblivios.decoy.content.check.RuleFixtures.birthdays;
import static org.oblivios.decoy.content.check.RuleFixtures.planted;
import static org.oblivios.decoy.content.check.RuleFixtures.sites;

import java.util.List;
import org.junit.jupiter.api.Test;

class PopulationRulesTest {
    @Test
    void sameSitesEverywhereAreReported() {
        List<String> one = List.of("a.example", "b.example", "c.example");
        List<Violation> found = planted(List.of(sites(one), sites(one), sites(one))).only("G-SIGNATURE");
        assertTrue(found.stream().anyMatch(v -> v.detail().contains("a.example")), "found " + found);
    }

    @Test
    void differentSitesPass() {
        List<Violation> found = planted(List.of(sites(List.of("a.example", "b.example")), sites(List.of("c.example", "d.example")),
                sites(List.of("e.example", "f.example")))).only("G-SIGNATURE");
        assertEquals(List.of(), found);
    }

    @Test
    void noBirthdayAfterThe28thIsReported() {
        assertEquals(1, planted(List.of(birthdays(1000))).only("G-SPREAD").size());
        assertEquals(0, planted(List.of(birthdays(999))).only("G-SPREAD").size(), "too few birthdays to judge");
    }
}
