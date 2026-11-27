// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.persona;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import org.oblivios.decoy.content.Persona;
import org.oblivios.decoy.content.Seed;
import org.oblivios.decoy.content.TestSetup;
import org.oblivios.decoy.content.data.Spreads;
import org.oblivios.decoy.content.harness.PlanHarness;
import org.oblivios.decoy.content.persona.PersonaProfile.JobKind;
import org.oblivios.decoy.content.persona.PersonaProfile.Pet;
import org.oblivios.decoy.content.persona.PersonaProfile.Transport;

/** The open answers of a persona are drawn by the figures of the spreads. */
class PersonaSpreadsTest {
    private static final int PERSONAS = 6_000;

    private static List<Persona> open(PersonaProfile profile) {
        List<Persona> out = new ArrayList<>();
        for (int i = 0; i < PERSONAS; i++) {
            out.add(TestSetup.derive(Seed.of(i, 77), profile, 2026));
        }
        return out;
    }

    private static double share(List<Persona> personas, Predicate<Persona> which) {
        return personas.stream().filter(which).count() / (double) personas.size();
    }

    @Test
    void petsAndTransportFollowTheirFigures() {
        Spreads nl = Spreads.load("NL");
        List<Persona> personas = open(PersonaProfile.builder(PlanHarness.inputs("nl", "NL")).birthYear(2026 - 42).build());
        for (Pet pet : Pet.values()) {
            assertEquals(nl.petPercent(pet) / 100.0,
                    share(personas, p -> p.pets().stream().anyMatch(a -> a.kind() == pet)), 0.02, pet.name());
        }
        int[] w = nl.transportWeights(42);
        double car = w[Transport.CAR.ordinal()] / (double) (w[0] + w[1] + w[2]);
        assertEquals(car, share(personas, p -> p.transport() == Transport.CAR), 0.03);
    }

    @Test
    void theJobFollowsTheLabourFiguresOfTheAge() {
        Spreads nl = Spreads.load("NL");
        List<Persona> personas = open(PersonaProfile.builder(PlanHarness.inputs("nl", "NL")).birthYear(2026 - 42).build());
        double self = 0;
        for (String sex : new String[] {"F", "M"}) {
            // an age of 41 or 42, by the birthday: both lie in one band, and the sexes are drawn half and half
            self += nl.value("labour." + sex + ".40-44.selfEmployed") / nl.value("labour." + sex + ".40-44.total") / 2;
        }
        assertEquals(self, share(personas, p -> p.job() == JobKind.SELF_EMPLOYED), 0.02);
    }
}
