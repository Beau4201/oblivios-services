// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.check.rules;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.oblivios.decoy.content.Persona;
import org.oblivios.decoy.content.check.ExaminerRule;
import org.oblivios.decoy.content.check.PhoneViews;
import org.oblivios.decoy.content.check.Violation;
import org.oblivios.decoy.content.country.CountryPack;
import org.oblivios.decoy.content.surface.Call;
import org.oblivios.decoy.content.surface.CallType;
import org.oblivios.decoy.content.surface.Voicemail;

/**
 * {@code C-CALLS}: {@value #MIN_OUTGOING} or more outgoing calls and not one that nobody answered (0 seconds); or a
 * voicemail without a missed call from its number in the {@value #VOICEMAIL_MINUTES} minutes before it.
 */
public final class CCalls implements ExaminerRule {
    static final int MIN_OUTGOING = 20;
    static final int VOICEMAIL_MINUTES = 5;

    @Override
    public String id() {
        return "C-CALLS";
    }

    @Override
    public void check(PhoneViews v, Persona persona, CountryPack pack, Instant now, List<Violation> out) {
        List<Call> outgoing = v.people().calls().stream().filter(c -> c.type() == CallType.OUTGOING).toList();
        if (outgoing.size() >= MIN_OUTGOING && outgoing.stream().noneMatch(c -> c.durationSeconds() == 0)) {
            out.add(new Violation(id(), outgoing.size() + " outgoing calls and every one was answered"));
        }
        for (Voicemail m : v.people().voicemails()) {
            Instant from = m.at().minus(Duration.ofMinutes(VOICEMAIL_MINUTES));
            boolean missed = v.people().calls().stream().anyMatch(c -> c.type() == CallType.MISSED && c.number().equals(m.fromNumber())
                    && !c.at().isBefore(from) && !c.at().isAfter(m.at()));
            if (!missed) {
                out.add(new Violation(id(), "voicemail of " + m.fromNumber() + " at " + m.at() + " follows no missed call"));
            }
        }
    }
}
