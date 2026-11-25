// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.surface;

import java.time.Instant;
import java.util.List;
import org.oblivios.decoy.content.Persona;

/**
 * What the roll-forward reads back from the decoy's own stores: the persona, the time of the latest event of any
 * surface and the contacts. It holds no seed (the decoy keeps none) and nothing of the owner.
 */
public record ContentSnapshot(Persona persona, Instant latestEvent, List<Contact> contacts) {
    public ContentSnapshot {
        contacts = List.copyOf(contacts);
    }
}
