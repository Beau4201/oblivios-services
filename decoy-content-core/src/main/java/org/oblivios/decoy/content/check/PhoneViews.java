// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.check;

import java.util.ArrayList;
import java.util.List;
import org.oblivios.decoy.content.plan.ContentPlan;
import org.oblivios.decoy.content.surface.AppsPlan;
import org.oblivios.decoy.content.surface.BrowserPlan;
import org.oblivios.decoy.content.surface.CalendarPlan;
import org.oblivios.decoy.content.surface.DevicePlan;
import org.oblivios.decoy.content.surface.MailPlan;
import org.oblivios.decoy.content.surface.MediaPlan;
import org.oblivios.decoy.content.surface.NetworkPlan;
import org.oblivios.decoy.content.surface.People;
import org.oblivios.decoy.content.surface.SmsThread;
import org.oblivios.decoy.content.surface.TracesPlan;
import org.oblivios.decoy.content.surface.UsagePlan;
import org.oblivios.decoy.content.surface.VaultPlan;

/**
 * What an examiner sees of a plan: the views of the phone's stores and nothing of the story. The travel plan is part of
 * the chronicle, not a view.
 */
public record PhoneViews(People people, CalendarPlan calendar, MediaPlan media, MailPlan mail, BrowserPlan browser, VaultPlan vault,
        NetworkPlan network, DevicePlan device, AppsPlan apps, UsagePlan usage, TracesPlan traces) {

    public static PhoneViews of(ContentPlan p) {
        return new PhoneViews(p.people(), p.calendar(), p.media(), p.mail(), p.browser(), p.vault(), p.network(), p.device(), p.apps(),
                p.usage(), p.traces());
    }

    /** Every item of every view: the records of each list, each thread followed by its messages, and the device plan. */
    public List<Object> items() {
        List<Object> out = new ArrayList<>(people.contacts());
        out.addAll(people.calls());
        out.addAll(people.voicemails());
        for (SmsThread t : people.threads()) {
            out.add(t);
            out.addAll(t.messages());
        }
        out.addAll(calendar.events());
        out.addAll(calendar.alarms());
        out.addAll(calendar.notes());
        out.addAll(media.items());
        out.addAll(mail.messages());
        out.addAll(browser.history());
        out.addAll(browser.cookies());
        out.addAll(browser.bookmarks());
        out.addAll(vault.entries());
        out.addAll(network.wifi());
        out.addAll(network.bluetooth());
        out.add(device);
        out.addAll(apps.apps());
        out.addAll(usage.sessions());
        out.addAll(traces.notifications());
        return out;
    }
}
