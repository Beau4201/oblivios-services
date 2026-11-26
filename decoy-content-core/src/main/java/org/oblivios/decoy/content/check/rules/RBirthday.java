// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.check.rules;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.oblivios.decoy.content.Persona;
import org.oblivios.decoy.content.check.ExaminerRule;
import org.oblivios.decoy.content.check.PhoneViews;
import org.oblivios.decoy.content.check.Violation;
import org.oblivios.decoy.content.country.CountryPack;
import org.oblivios.decoy.content.surface.CalEvent;
import org.oblivios.decoy.content.surface.Contact;

/** {@code R-BIRTHDAY}, both ways: a contact's birthday has its yearly event, and a yearly event belongs to a contact with that birthday. */
public final class RBirthday implements ExaminerRule {
    @Override
    public String id() {
        return "R-BIRTHDAY";
    }

    @Override
    public void check(PhoneViews v, Persona persona, CountryPack pack, Instant now, List<Violation> out) {
        for (Contact c : v.people().contacts()) {
            if (c.birthday() != null && v.calendar().events().stream().noneMatch(e -> birthdayOf(e, c))) {
                out.add(new Violation(id(), "contact " + c.name() + " has a birthday and no yearly event"));
            }
        }
        for (CalEvent e : v.calendar().events()) {
            if (e.yearly() && v.people().contacts().stream().noneMatch(c -> c.birthday() != null && birthdayOf(e, c))) {
                out.add(new Violation(id(), "yearly event '" + e.title() + "' belongs to no contact with that birthday"));
            }
        }
    }

    private static boolean birthdayOf(CalEvent e, Contact c) {
        return e.yearly() && c.birthday() != null && e.title().contains(c.name())
                && e.start().atZone(ZoneOffset.UTC).getMonth() == c.birthday().getMonth()
                && e.start().atZone(ZoneOffset.UTC).getDayOfMonth() == c.birthday().getDayOfMonth();
    }
}
