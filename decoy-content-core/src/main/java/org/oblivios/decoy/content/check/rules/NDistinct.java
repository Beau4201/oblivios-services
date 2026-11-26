// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.check.rules;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.oblivios.decoy.content.Persona;
import org.oblivios.decoy.content.check.ExaminerRule;
import org.oblivios.decoy.content.check.PhoneViews;
import org.oblivios.decoy.content.check.Violation;
import org.oblivios.decoy.content.country.CountryPack;
import org.oblivios.decoy.content.surface.Contact;

/**
 * {@code N-DISTINCT}: two contacts saved under one label, or a label that is a given name alone beside another label
 * that starts with that given name ('Sam' beside 'Sam Example'): an owner tells two people apart when saving them.
 * Labels are compared ignoring case and the spaces around them.
 */
public final class NDistinct implements ExaminerRule {
    @Override
    public String id() {
        return "N-DISTINCT";
    }

    @Override
    public void check(PhoneViews v, Persona persona, CountryPack pack, Instant now, List<Violation> out) {
        Map<String, Integer> labels = new LinkedHashMap<>();
        for (Contact c : v.people().contacts()) {
            labels.merge(c.name().trim().toLowerCase(Locale.ROOT), 1, Integer::sum);
        }
        Map<String, Set<String>> byFirst = new LinkedHashMap<>();
        labels.forEach((label, n) -> {
            if (n > 1) {
                out.add(new Violation(id(), n + " contacts are saved as '" + label + "'"));
            }
            byFirst.computeIfAbsent(label.split("\\s+")[0], k -> new LinkedHashSet<>()).add(label);
        });
        byFirst.forEach((first, set) -> {
            if (set.size() > 1 && set.contains(first)) {
                out.add(new Violation(id(), "a contact is saved as '" + first + "' alone beside " + set.size() + " labels of that name"));
            }
        });
    }
}
