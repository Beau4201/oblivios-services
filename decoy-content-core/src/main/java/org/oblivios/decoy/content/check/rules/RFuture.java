// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.check.rules;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.BiConsumer;
import org.oblivios.decoy.content.check.OriginRule;
import org.oblivios.decoy.content.check.PhoneViews;
import org.oblivios.decoy.content.check.PlanStory;
import org.oblivios.decoy.content.check.Violation;
import org.oblivios.decoy.content.surface.CalendarSurface;

/**
 * {@code R-FUTURE}: an instant of a view or a span away lies after the moment the story ends; a calendar entry may reach
 * the horizon past it. An origin rule because the spans away are part of the chronicle, not a view.
 */
public final class RFuture implements OriginRule {
    @Override
    public String id() {
        return "R-FUTURE";
    }

    @Override
    public void check(PhoneViews v, PlanStory story, List<Violation> out) {
        Instant now = story.now();
        BiConsumer<String, Instant> late = (what, at) -> {
            if (at != null && at.isAfter(now)) {
                out.add(new Violation(id(), what + " at " + at + " lies after " + now));
            }
        };
        v.people().calls().forEach(c -> late.accept("call", c.at()));
        v.people().threads().forEach(t -> t.messages().forEach(m -> late.accept("SMS", m.at())));
        v.people().voicemails().forEach(m -> late.accept("voicemail", m.at()));
        v.people().contacts().forEach(c -> late.accept("last contact of " + c.name(), c.lastContacted()));
        // a calendar entry may lie ahead, up to the horizon the generator uses
        Instant horizon = now.plus(Duration.ofDays(CalendarSurface.HORIZON_DAYS));
        v.calendar().events().forEach(e -> {
            if (e.start().isAfter(horizon)) {
                out.add(new Violation(id(), "calendar entry '" + e.title() + "' at " + e.start() + " lies after the horizon " + horizon));
            }
        });
        v.calendar().notes().forEach(n -> late.accept("note", n.created()));
        story.away().forEach(s -> late.accept("span away " + s.what(), s.to()));
        v.media().items().forEach(i -> late.accept("taken " + i.name(), i.taken()));
        v.media().items().forEach(i -> late.accept("added " + i.name(), i.added()));
        v.mail().messages().forEach(m -> late.accept("mail '" + m.subject() + "'", m.date()));
        v.network().wifi().forEach(w -> late.accept("network " + w.ssid(), w.savedAt()));
        v.network().bluetooth().forEach(b -> late.accept("pairing " + b.name(), b.pairedAt()));
        v.usage().sessions().forEach(s -> late.accept("usage session of " + s.pkg(), s.start()));
        v.usage().lastUsed().forEach((pkg, at) -> late.accept("last use of " + pkg, at));
        v.apps().apps().forEach(a -> late.accept("install of " + a.pkg(), a.installed()));
        v.browser().history().forEach(h -> late.accept("visit of " + h.domain(), h.at()));
        v.traces().notifications().forEach(n -> late.accept("notification of " + n.pkg(), n.at()));
    }
}
