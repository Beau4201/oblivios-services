// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.pool;

import java.util.List;
import org.oblivios.decoy.content.Persona;
import org.oblivios.decoy.content.surface.Contact;

/** Judges whether a rewritten conversation fits the persona and its contacts; the local model implements it on the device. */
public interface Judge {
    record Verdict(boolean ok, String reason) {}

    Verdict judge(FilledConversation c, Persona p, List<Contact> others);
}
