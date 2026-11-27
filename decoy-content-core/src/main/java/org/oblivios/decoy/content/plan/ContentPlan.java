// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.plan;

import java.util.List;
import java.util.Map;
import org.oblivios.decoy.content.Persona;
import org.oblivios.decoy.content.chronicle.Chronicle;
import org.oblivios.decoy.content.world.World;
import org.oblivios.decoy.content.pool.FilledConversation;
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

/**
 * Everything the decoy shows, behind one life story. {@code conversations} are the pool conversations of the personal
 * and group threads; {@code reserve} the rewritten conversations kept back for the roll-forward. {@code warnings} gather
 * what the planner defaulted or dropped, for the setup to tell the owner. {@code dataVersions} holds the version of every
 * name file that was read and, as {@code pool}, a digest of the conversation pool, so that a plan can be built again.
 * {@code simNumber} is the setup's number of the SIM, the only number a view may state as the phone's own.
 */
public record ContentPlan(Persona persona, World world, Chronicle chronicle, Timeline timeline, People people, CalendarPlan calendar, TravelPlan travel, DevicePlan device,
        NetworkPlan network, UsagePlan usage, AppsPlan apps, BrowserPlan browser, VaultPlan vault, TracesPlan traces, MediaPlan media,
        MailPlan mail, StyleBook styles, List<FilledConversation> conversations, List<FilledConversation> reserve, List<String> warnings,
        Map<String, String> dataVersions, String simNumber) {
    public ContentPlan {
        dataVersions = Map.copyOf(dataVersions);
        conversations = List.copyOf(conversations);
        reserve = List.copyOf(reserve);
        warnings = List.copyOf(warnings);
    }
}
