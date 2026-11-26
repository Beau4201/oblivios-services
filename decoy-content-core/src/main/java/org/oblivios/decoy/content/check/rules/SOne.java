// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.check.rules;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.oblivios.decoy.content.Persona;
import org.oblivios.decoy.content.check.ExaminerRule;
import org.oblivios.decoy.content.check.PhoneViews;
import org.oblivios.decoy.content.check.Violation;
import org.oblivios.decoy.content.country.CountryPack;
import org.oblivios.decoy.content.country.Sender;
import org.oblivios.decoy.content.country.SenderKind;
import org.oblivios.decoy.content.surface.MailMessage;
import org.oblivios.decoy.content.surface.SmsThread;

/** {@code S-ONE}: the SMS threads and the mail of the phone come from more than one bank, or from more than one operator, of the pack. */
public final class SOne implements ExaminerRule {
    @Override
    public String id() {
        return "S-ONE";
    }

    @Override
    public void check(PhoneViews v, Persona persona, CountryPack pack, Instant now, List<Violation> out) {
        for (SenderKind kind : List.of(SenderKind.BANK, SenderKind.OPERATOR)) {
            Set<String> names = new TreeSet<>(pack.senders(kind).stream().map(Sender::name).toList());
            Set<String> used = new TreeSet<>();
            v.people().threads().stream().map(SmsThread::address).filter(names::contains).forEach(used::add);
            v.mail().messages().stream().map(MailMessage::from).filter(names::contains).forEach(used::add);
            if (used.size() > 1) {
                out.add(new Violation(id(), "SMS and mail from " + used.size() + " senders of kind " + kind + ": " + used));
            }
        }
    }
}
