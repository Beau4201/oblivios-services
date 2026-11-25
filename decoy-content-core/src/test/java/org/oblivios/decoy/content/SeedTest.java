// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.ZoneId;
import java.util.Locale;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.Test;
import org.oblivios.decoy.content.persona.PersonaProfile;

class SeedTest {

    private static long[] draw(RandomGenerator r, int n) {
        long[] out = new long[n];
        for (int i = 0; i < n; i++) {
            out[i] = r.nextLong();
        }
        return out;
    }

    private static PersonaInputs inputs() {
        return new PersonaInputs("Sam Example", "Utrecht", "NL", ZoneId.of("Europe/Amsterdam"), Locale.forLanguageTag("nl-NL"));
    }

    @Test
    void sameSeedSameStream() {
        assertEquals(
                java.util.Arrays.toString(draw(Seed.of(1, 2).stream("contacts"), 8)),
                java.util.Arrays.toString(draw(Seed.of(1, 2).stream("contacts"), 8)));
    }

    @Test
    void differentStreamsDiffer() {
        Seed s = Seed.of(1, 2);
        assertNotEquals(s.stream("contacts").nextLong(), s.stream("calls").nextLong());
    }

    @Test
    void differentSeedsDiffer() {
        assertNotEquals(Seed.of(1, 2).stream("contacts").nextLong(), Seed.of(1, 3).stream("contacts").nextLong());
    }

    @Test
    void streamIndependence() {
        Seed s = Seed.of(7, 9);
        long before = s.stream("calls").nextLong();
        draw(s.stream("contacts"), 100);
        assertEquals(before, s.stream("calls").nextLong());
    }

    @Test
    void personaIsDeterministic() {
        assertEquals(Persona.derive(Seed.of(5, 6), PersonaProfile.open(inputs()), 2026), Persona.derive(Seed.of(5, 6), PersonaProfile.open(inputs()), 2026));
    }

    @Test
    void personaStaysInPlausibleRanges() {
        for (long i = 0; i < 200; i++) {
            Persona p = Persona.derive(Seed.of(i, 1), PersonaProfile.open(inputs()), 2026);
            assertTrue(p.birthYear() >= 2026 - 70 && p.birthYear() <= 2026 - 16, "birth year " + p.birthYear());
            assertTrue(p.household() >= 1 && p.household() <= 12, "household " + p.household());
            assertTrue(p.job() != null);
        }
    }

    @Test
    void randomSeedsDiffer() {
        assertNotEquals(Seed.random().stream("x").nextLong(), Seed.random().stream("x").nextLong());
    }
}
