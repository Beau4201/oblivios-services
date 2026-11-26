// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.check.rules;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import org.oblivios.decoy.content.check.PhoneViews;
import org.oblivios.decoy.content.check.PopulationRule;
import org.oblivios.decoy.content.check.Violation;
import org.oblivios.decoy.content.surface.Contact;

/**
 * {@code G-SPREAD}: over {@value #MIN_BIRTHDAYS} birthdays or more, none falls on day 29, 30 or 31, while about one in
 * ten of real birthdays does.
 */
public final class GSpread implements PopulationRule {
    static final int MIN_BIRTHDAYS = 1000;

    @Override
    public String id() {
        return "G-SPREAD";
    }

    @Override
    public void check(List<PhoneViews> plans, List<Violation> out) {
        List<LocalDate> days = plans.stream().flatMap(v -> v.people().contacts().stream()).map(Contact::birthday).filter(Objects::nonNull)
                .toList();
        if (days.size() >= MIN_BIRTHDAYS && days.stream().noneMatch(d -> d.getDayOfMonth() >= 29)) {
            out.add(new Violation(id(), "none of " + days.size() + " birthdays falls after the 28th"));
        }
    }
}
