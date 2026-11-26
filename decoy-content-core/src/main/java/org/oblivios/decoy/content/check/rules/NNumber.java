// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.check.rules;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.oblivios.decoy.content.Persona;
import org.oblivios.decoy.content.check.ExaminerRule;
import org.oblivios.decoy.content.check.PhoneViews;
import org.oblivios.decoy.content.check.Violation;
import org.oblivios.decoy.content.country.CountryPack;
import org.oblivios.decoy.content.country.PhoneNumber;
import org.oblivios.decoy.content.country.PhoneNumber.NumberType;
import org.oblivios.decoy.content.data.PhoneNumbers;
import org.oblivios.decoy.content.surface.SmsThread;

/**
 * {@code N-NUMBER}: a number of a contact, call, voicemail or thread that no person of the country can have (a wrong
 * form, or a paid, toll-free, short or emergency number), or an own number other than the SIM's. A thread whose address
 * is a sender's name holds no number.
 * shortcut: every number is judged against the persona's country; a contact abroad needs its own (plan Task 9).
 */
public final class NNumber implements ExaminerRule {
    private static final Pattern NUMBER = Pattern.compile("\\+?[0-9]+");

    @Override
    public String id() {
        return "N-NUMBER";
    }

    @Override
    public void check(PhoneViews v, Persona persona, CountryPack pack, Instant now, List<Violation> out) {
        if (!v.people().ownNumber().equals(v.simNumber())) {
            out.add(new Violation(id(), "the plan's own number " + v.people().ownNumber() + " is not the SIM's"));
        }
        Set<String> numbers = new LinkedHashSet<>();
        v.people().contacts().forEach(c -> numbers.add(c.number()));
        v.people().calls().forEach(c -> numbers.add(c.number()));
        v.people().voicemails().forEach(m -> numbers.add(m.fromNumber()));
        for (SmsThread t : v.people().threads()) {
            for (String address : t.address().split(";")) {
                if (NUMBER.matcher(address).matches()) {
                    numbers.add(address);
                }
            }
        }
        PhoneNumbers country = PhoneNumbers.of(pack.code());
        for (String n : numbers) {
            if (!country.valid(new PhoneNumber(n, NumberType.MOBILE)) && !country.valid(new PhoneNumber(n, NumberType.FIXED))) {
                out.add(new Violation(id(), n + " is no number a person of " + pack.code() + " can have"));
            }
        }
    }
}
