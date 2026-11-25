// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.ZoneId;
import java.util.Locale;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.Test;

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
        assertEquals(Persona.derive(Seed.of(5, 6), inputs()), Persona.derive(Seed.of(5, 6), inputs()));
    }

    @Test
    void personaStaysInPlausibleRanges() {
        for (long i = 0; i < 200; i++) {
            Persona p = Persona.derive(Seed.of(i, 1), inputs());
            assertTrue(p.birthYear() >= 1970 && p.birthYear() <= 2002, "birth year " + p.birthYear());
            assertTrue(p.householdSize() >= 1 && p.householdSize() <= 5, "household " + p.householdSize());
            assertTrue(!p.occupation().isBlank());
        }
    }

    @Test
    void randomSeedsDiffer() {
        assertNotEquals(Seed.random().stream("x").nextLong(), Seed.random().stream("x").nextLong());
    }
}
