// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.check.rules;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.oblivios.decoy.content.chronicle.Event;
import org.oblivios.decoy.content.chronicle.EventKind;
import org.oblivios.decoy.content.check.OriginRule;
import org.oblivios.decoy.content.check.PhoneViews;
import org.oblivios.decoy.content.check.PlanStory;
import org.oblivios.decoy.content.check.Violation;
import org.oblivios.decoy.content.surface.Call;
import org.oblivios.decoy.content.surface.CallType;
import org.oblivios.decoy.content.surface.MediaItem;
import org.oblivios.decoy.content.surface.MediaKind;
import org.oblivios.decoy.content.surface.SmsMessage;
import org.oblivios.decoy.content.surface.SmsThread;
import org.oblivios.decoy.content.surface.UsageSession;
import org.oblivios.decoy.content.surface.Visit;

/**
 * {@code T-ASLEEP}: something the persona did, a call made or answered, a message sent, a photo, a page or an app,
 * more than {@value #EDGE_MINUTES} minutes from both ends of a sleep. The edge is {@code [assumption]}: a look at
 * the phone before sleeping or on waking.
 */
public final class TAsleep implements OriginRule {
    static final int EDGE_MINUTES = 30;

    @Override
    public String id() {
        return "T-ASLEEP";
    }

    @Override
    public void check(PhoneViews v, PlanStory story, List<Violation> out) {
        List<Event> sleeps = story.chronicle().events().stream().filter(e -> e.kind() == EventKind.SLEEP).toList();
        for (Call c : v.people().calls()) {
            if (c.type() == CallType.OUTGOING || c.type() == CallType.INCOMING && c.durationSeconds() > 0) {
                judge("call", c.at(), sleeps, out);
            }
        }
        for (SmsThread t : v.people().threads()) {
            for (SmsMessage m : t.messages()) {
                if (!m.incoming()) {
                    judge("message", m.at(), sleeps, out);
                }
            }
        }
        for (MediaItem i : v.media().items()) {
            if (i.kind() == MediaKind.CAMERA_FILL || i.kind() == MediaKind.CAMERA_CHOSEN) {
                judge("photo", i.taken(), sleeps, out);
            }
        }
        for (Visit visit : v.browser().history()) {
            judge("page visit", visit.at(), sleeps, out);
        }
        for (UsageSession s : v.usage().sessions()) {
            judge("app session", s.start(), sleeps, out);
        }
    }

    private void judge(String what, Instant at, List<Event> sleeps, List<Violation> out) {
        Duration edge = Duration.ofMinutes(EDGE_MINUTES);
        for (Event sleep : sleeps) {
            if (at.isAfter(sleep.start().plus(edge)) && at.isBefore(sleep.end().minus(edge))) {
                out.add(new Violation(id(), what + " at " + at + " in the sleep from " + sleep.start()));
                return;
            }
        }
    }
}
