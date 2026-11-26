// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.check.rules;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.oblivios.decoy.content.Persona;
import org.oblivios.decoy.content.check.ExaminerRule;
import org.oblivios.decoy.content.check.PhoneViews;
import org.oblivios.decoy.content.check.Violation;
import org.oblivios.decoy.content.country.CountryPack;
import org.oblivios.decoy.content.country.SenderKind;

/** {@code R-SMS-SENDER}: a one-to-one thread with an address that is no contact and no sender of the country pack. */
public final class RSmsSender implements ExaminerRule {
    @Override
    public String id() {
        return "R-SMS-SENDER";
    }

    @Override
    public void check(PhoneViews v, Persona persona, CountryPack pack, Instant now, List<Violation> out) {
        Set<String> known = new HashSet<>();
        v.people().contacts().forEach(c -> known.add(c.number()));
        for (SenderKind kind : SenderKind.values()) {
            pack.senders(kind).forEach(s -> known.add(s.name()));
        }
        v.people().threads().forEach(t -> {
            if (t.members().isEmpty() && !known.contains(t.address())) { // a group thread is checked by its members
                out.add(new Violation(id(), "thread with " + t.address() + ", which is no contact and no sender of the country pack"));
            }
        });
    }
}
