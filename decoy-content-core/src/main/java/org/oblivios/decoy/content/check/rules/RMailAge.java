// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.check.rules;

import java.time.Instant;
import java.util.List;
import org.oblivios.decoy.content.check.OriginRule;
import org.oblivios.decoy.content.check.PhoneViews;
import org.oblivios.decoy.content.check.PlanStory;
import org.oblivios.decoy.content.check.Violation;

/** {@code R-MAIL-AGE}: a mail older than the start of the phone. */
public final class RMailAge implements OriginRule {
    @Override
    public String id() {
        return "R-MAIL-AGE";
    }

    @Override
    public void check(PhoneViews v, PlanStory story, List<Violation> out) {
        Instant start = story.phoneStart();
        v.mail().messages().forEach(m -> {
            if (m.date().isBefore(start)) {
                out.add(new Violation(id(), "mail '" + m.subject() + "' of " + m.date() + " is older than the phone (" + start + ")"));
            }
        });
    }
}
