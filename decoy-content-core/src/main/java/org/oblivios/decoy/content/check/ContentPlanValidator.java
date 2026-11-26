// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.check;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.oblivios.decoy.content.plan.ContentPlan;

/**
 * The checker as it was before the life story: the twelve {@code R-} rules, now classes of {@link Checks}. It runs
 * those rules only; {@link Checks#all} runs every rule of both checkers.
 */
public final class ContentPlanValidator {
    /** The rules this class held before the checkers existed. */
    public static final Set<String> RULES = Set.of("R-PHOTO-PLACE", "R-CALL-CONTACT", "R-SMS-SENDER", "R-BIRTHDAY", "R-WIFI-PLACE",
            "R-FUTURE", "R-MAIL-AGE", "R-SLOT", "R-REQUIRES", "R-REGISTER", "R-UNREWRITTEN", "R-GROUP-MEMBER");

    private ContentPlanValidator() {}

    /** The violations of the twelve {@code R-} rules; an empty list is a plan clean of them. */
    public static List<Violation> check(ContentPlan p) {
        Set<String> off = new HashSet<>(Checks.ids());
        off.removeAll(RULES);
        PhoneViews v = PhoneViews.of(p);
        List<Violation> out = Checks.examine(v, p.persona(), Checks.packOf(p.persona()), p.timeline().now(), off);
        out.addAll(Checks.origins(v, PlanStory.of(p), off));
        return out;
    }
}
