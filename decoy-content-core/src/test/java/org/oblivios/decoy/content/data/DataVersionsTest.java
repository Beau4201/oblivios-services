// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;
import org.oblivios.decoy.content.Seed;
import org.oblivios.decoy.content.harness.PlanHarness;
import org.oblivios.decoy.content.persona.PersonaProfile;
import org.oblivios.decoy.content.plan.ContentPlan;

class DataVersionsTest {
    private static PersonaProfile.Builder profile() {
        return PersonaProfile.builder(PlanHarness.inputs("nl", "NL"));
    }

    @Test
    void aPlanNamesTheVersionOfEveryDataFileItRead() {
        ContentPlan p = PlanHarness.plan(profile().background("NL").build(), Seed.of(1, 2));
        assertEquals(NameData.load("NL").version(), p.dataVersions().get("names/NL"));
        // a given background draws none from the population figures
        assertEquals(Set.of("names/NL", "places/NL", "holidays/NL", "numbers/NL", "spreads/NL", "interests/NL",
                "pool"), p.dataVersions().keySet());
        assertEquals(PlaceData.load("NL").version(), p.dataVersions().get("places/NL"));
        assertTrue(PlanHarness.plan(profile().build(), Seed.of(1, 2)).dataVersions().containsKey("population/NL"));
        assertTrue(p.dataVersions().get("pool").matches("sha256:[0-9a-f]{16}"), p.dataVersions().get("pool"));
        assertEquals(p.dataVersions(), PlanHarness.plan(profile().background("NL").build(), Seed.of(3, 4)).dataVersions());
    }

    @Test
    void aBackgroundWithoutNameDataIsRefusedByItsField() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> PlanHarness.plan(profile().background("IQ").build(), Seed.of(1, 2)));
        assertEquals("background: no name data for background IQ", e.getMessage());
    }
}
