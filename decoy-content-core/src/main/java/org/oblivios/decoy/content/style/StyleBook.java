// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.style;

import java.util.Map;
import org.oblivios.decoy.content.surface.Relation;

/** How the persona writes, how it writes towards each relation, and how each contact writes (by contact id). */
public record StyleBook(StyleVector persona, Map<Relation, StyleVector> personaTowards, Map<String, StyleVector> contacts) {
    public StyleBook {
        personaTowards = Map.copyOf(personaTowards);
        contacts = Map.copyOf(contacts);
    }
}
