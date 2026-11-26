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
import org.oblivios.decoy.content.surface.Cookie;

/**
 * {@code E-STALE}: a cookie that has expired at the moment the story ends (a browser removes it), or a mailbox with mail
 * and none in the {@value #MAIL_DAYS} days before that moment.
 */
public final class EStale implements ExaminerRule {
    static final int MAIL_DAYS = 30;

    @Override
    public String id() {
        return "E-STALE";
    }

    @Override
    public void check(PhoneViews v, Persona persona, CountryPack pack, Instant now, List<Violation> out) {
        List<Cookie> expired = v.browser().cookies().stream().filter(c -> c.expires() != null && !c.expires().isAfter(now)).toList();
        if (!expired.isEmpty()) {
            out.add(new Violation(id(), expired.size() + " cookies have expired, first: " + expired.get(0)));
        }
        Instant since = now.minus(Duration.ofDays(MAIL_DAYS));
        if (!v.mail().messages().isEmpty() && v.mail().messages().stream().noneMatch(m -> m.date().isAfter(since) && !m.date().isAfter(now))) {
            out.add(new Violation(id(), "a mailbox of " + v.mail().messages().size() + " mails has none in the last " + MAIL_DAYS + " days"));
        }
    }
}
