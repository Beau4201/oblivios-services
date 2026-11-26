// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

class DrawsTest {
    private static long first(Draws d, String name) {
        return d.stream(name).nextLong();
    }

    @Test
    void sameNameSameStream() {
        Draws d = Draws.of(Seed.of(1, 2));
        assertEquals(first(d, "world.person.3.given"), first(d, "world.person.3.given"));
        assertEquals(Seed.of(1, 2).stream("world.person.3.given").nextLong(), first(d, "world.person.3.given"),
                "without a redraw the stream is the seed's own");
        assertNotEquals(first(d, "world.person.3.given"), first(d, "world.person.4.given"));
    }

    @Test
    void redrawMovesOnlyItsItem() {
        Draws d = Draws.of(Seed.of(1, 2));
        Draws salted = d.redraw("world.person.3", 1);
        assertNotEquals(first(d, "world.person.3"), first(salted, "world.person.3"));
        assertNotEquals(first(d, "world.person.3.given"), first(salted, "world.person.3.given"));
        assertEquals(first(d, "world.person.4.given"), first(salted, "world.person.4.given"));
        assertEquals(first(d, "world.person.30.given"), first(salted, "world.person.30.given"), "30 is not part of 3");
        assertEquals(first(d, "world.person.3.given"), first(d, "world.person.3.given"), "a redraw leaves the old value alone");
    }

    @Test
    void theLongestRedrawnItemSalts() {
        Draws base = Draws.of(Seed.of(1, 2));
        Draws person = base.redraw("world.person.3", 1);
        Draws both = person.redraw("world.person", 5);
        assertEquals(first(person, "world.person.3.given"), first(both, "world.person.3.given"));
        assertNotEquals(first(base, "world.person.4.given"), first(both, "world.person.4.given"));
        assertNotEquals(first(person, "world.person.3.given"), first(base.redraw("world.person.3", 2), "world.person.3.given"));
    }
}
