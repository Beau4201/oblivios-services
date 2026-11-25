// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.plan;

import java.util.List;
import org.oblivios.decoy.content.Persona;
import org.oblivios.decoy.content.story.Timeline;
import org.oblivios.decoy.content.style.StyleBook;
import org.oblivios.decoy.content.surface.AppsPlan;
import org.oblivios.decoy.content.surface.BrowserPlan;
import org.oblivios.decoy.content.surface.CalendarPlan;
import org.oblivios.decoy.content.surface.DevicePlan;
import org.oblivios.decoy.content.surface.MailPlan;
import org.oblivios.decoy.content.surface.MediaPlan;
import org.oblivios.decoy.content.surface.NetworkPlan;
import org.oblivios.decoy.content.surface.People;
import org.oblivios.decoy.content.surface.TracesPlan;
import org.oblivios.decoy.content.surface.TravelPlan;
import org.oblivios.decoy.content.surface.UsagePlan;
import org.oblivios.decoy.content.surface.VaultPlan;

/** Everything the decoy shows, behind one life story. {@code warnings} gather what the planner defaulted or dropped, for the setup to tell the owner. */
public record ContentPlan(Persona persona, Timeline timeline, People people, CalendarPlan calendar, TravelPlan travel, DevicePlan device,
        NetworkPlan network, UsagePlan usage, AppsPlan apps, BrowserPlan browser, VaultPlan vault, TracesPlan traces, MediaPlan media,
        MailPlan mail, StyleBook styles, List<String> warnings) {
    public ContentPlan {
        warnings = List.copyOf(warnings);
    }
}
