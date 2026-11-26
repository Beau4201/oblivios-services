// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.check.rules;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.oblivios.decoy.content.check.Checks;
import org.oblivios.decoy.content.check.OriginRule;
import org.oblivios.decoy.content.check.PhoneViews;
import org.oblivios.decoy.content.check.PlanStory;
import org.oblivios.decoy.content.check.Violation;
import org.oblivios.decoy.content.origin.Origin;
import org.oblivios.decoy.content.origin.Sourced;
import org.oblivios.decoy.content.surface.Alarm;
import org.oblivios.decoy.content.surface.AppEntry;
import org.oblivios.decoy.content.surface.BluetoothPair;
import org.oblivios.decoy.content.surface.CalEvent;
import org.oblivios.decoy.content.surface.Call;
import org.oblivios.decoy.content.surface.Contact;
import org.oblivios.decoy.content.surface.Cookie;
import org.oblivios.decoy.content.surface.DevicePlan;
import org.oblivios.decoy.content.surface.MailMessage;
import org.oblivios.decoy.content.surface.MediaItem;
import org.oblivios.decoy.content.surface.Note;
import org.oblivios.decoy.content.surface.NotificationRecord;
import org.oblivios.decoy.content.surface.SmsMessage;
import org.oblivios.decoy.content.surface.SmsThread;
import org.oblivios.decoy.content.surface.UsageSession;
import org.oblivios.decoy.content.surface.VaultEntry;
import org.oblivios.decoy.content.surface.Visit;
import org.oblivios.decoy.content.surface.Voicemail;
import org.oblivios.decoy.content.surface.WifiNetwork;

/**
 * {@code G-ORIGIN}: an item of a fact-bearing type that names no origin, or an item whose origin the story does not
 * hold. One violation per type and kind of fault, with the count and the first item, so a plan of thousands of items
 * gives a short report.
 */
public final class GOrigin implements OriginRule {
    static final Set<Class<?>> FACT_BEARING = Set.of(Contact.class, Call.class, Voicemail.class, SmsThread.class, SmsMessage.class,
            CalEvent.class, Alarm.class, Note.class, MediaItem.class, MailMessage.class, Visit.class, Cookie.class, VaultEntry.class,
            WifiNetwork.class, BluetoothPair.class, NotificationRecord.class, UsageSession.class, AppEntry.class, DevicePlan.class);

    @Override
    public String id() {
        return "G-ORIGIN";
    }

    @Override
    public void check(PhoneViews v, PlanStory story, List<Violation> out) {
        judge(v.items(), story, out);
    }

    /** Judges each item: a {@link Sourced} item by its origin, any other item by whether its type bears a fact. */
    public static void judge(List<?> items, PlanStory story, List<Violation> out) {
        Map<String, Object[]> missing = new LinkedHashMap<>();
        Map<String, Object[]> unresolved = new LinkedHashMap<>();
        for (Object item : items) {
            String type = item.getClass().getSimpleName();
            if (item instanceof Sourced s) {
                Origin o = s.origin();
                if (o == null || story.resolve(o).isEmpty()) {
                    Object[] seen = unresolved.computeIfAbsent(type, k -> new Object[] {0, o});
                    seen[0] = (Integer) seen[0] + 1;
                }
            } else if (FACT_BEARING.contains(item.getClass())) {
                Object[] seen = missing.computeIfAbsent(type, k -> new Object[] {0, item});
                seen[0] = (Integer) seen[0] + 1;
            }
        }
        missing.forEach((type, seen) -> out.add(new Violation("G-ORIGIN", seen[0] + " " + type + " without an origin, first: "
                + Checks.shown(seen[1]))));
        unresolved.forEach((type, seen) -> out.add(new Violation("G-ORIGIN", seen[0] + " " + type + " whose origin the story does not hold, first: "
                + seen[1], seen[1] == null ? List.of() : List.of((Origin) seen[1]))));
    }
}
