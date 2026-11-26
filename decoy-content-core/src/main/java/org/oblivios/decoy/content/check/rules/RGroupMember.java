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
import org.oblivios.decoy.content.surface.SmsMessage;
import org.oblivios.decoy.content.surface.SmsThread;

/** {@code R-GROUP-MEMBER}: a group thread holds a member who is no contact, or an incoming message from no member. */
public final class RGroupMember implements ExaminerRule {
    @Override
    public String id() {
        return "R-GROUP-MEMBER";
    }

    @Override
    public void check(PhoneViews v, Persona persona, CountryPack pack, Instant now, List<Violation> out) {
        Set<String> ids = new HashSet<>();
        v.people().contacts().forEach(c -> ids.add(c.id()));
        for (SmsThread t : v.people().threads()) {
            for (String member : t.members()) {
                if (!ids.contains(member)) {
                    out.add(new Violation(id(), "group " + t.title() + " holds " + member + ", which is no contact"));
                }
            }
            if (t.members().isEmpty()) {
                continue;
            }
            for (SmsMessage m : t.messages()) {
                // an immutable list refuses a null in contains, so the missing sender is tested first
                if (m.incoming() && (m.from() == null || !t.members().contains(m.from()))) {
                    out.add(new Violation(id(), "group " + t.title() + " has a message at " + m.at() + " from " + m.from() + ", which is no member"));
                }
            }
        }
    }
}
