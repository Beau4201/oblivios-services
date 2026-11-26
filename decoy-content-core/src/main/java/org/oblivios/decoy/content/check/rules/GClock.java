// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.check.rules;

import java.time.Instant;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import org.oblivios.decoy.content.Persona;
import org.oblivios.decoy.content.check.ExaminerRule;
import org.oblivios.decoy.content.check.PhoneViews;
import org.oblivios.decoy.content.check.Violation;
import org.oblivios.decoy.content.country.CountryPack;
import org.oblivios.decoy.content.surface.CalEvent;
import org.oblivios.decoy.content.surface.Call;
import org.oblivios.decoy.content.surface.MailMessage;
import org.oblivios.decoy.content.surface.MediaItem;
import org.oblivios.decoy.content.surface.Note;
import org.oblivios.decoy.content.surface.Visit;

/**
 * {@code G-CLOCK}: more than {@value #EXACT_PERCENT} percent of the logged instants (calls, messages, visits, media
 * added, notes, mail) fall on an exact minute, with no seconds and no milliseconds, which a phone's own log almost
 * never does (one in 60,000); or an appointment that is not all-day starts off a five-minute grid in the persona's
 * zone. Today's calendar has no all-day flag: a yearly entry (a birthday) counts as all-day.
 */
public final class GClock implements ExaminerRule {
    static final int EXACT_PERCENT = 5;

    @Override
    public String id() {
        return "G-CLOCK";
    }

    @Override
    public void check(PhoneViews v, Persona persona, CountryPack pack, Instant now, List<Violation> out) {
        List<Instant> logged = new ArrayList<>();
        v.people().calls().stream().map(Call::at).forEach(logged::add);
        v.people().threads().forEach(t -> t.messages().forEach(m -> logged.add(m.at())));
        v.browser().history().stream().map(Visit::at).forEach(logged::add);
        v.media().items().stream().map(MediaItem::added).forEach(logged::add);
        v.calendar().notes().stream().map(Note::created).forEach(logged::add);
        v.mail().messages().stream().map(MailMessage::date).forEach(logged::add);
        long exact = logged.stream().filter(at -> at != null && at.getNano() == 0 && at.getEpochSecond() % 60 == 0).count();
        if (exact * 100 > (long) EXACT_PERCENT * logged.size()) {
            out.add(new Violation(id(), exact + " of " + logged.size() + " logged instants are on an exact minute"));
        }
        int offGrid = 0;
        String first = null;
        for (CalEvent e : v.calendar().events()) {
            if (e.yearly()) {
                continue;
            }
            LocalTime t = e.start().atZone(persona.inputs().zone()).toLocalTime();
            if (t.getMinute() % 5 != 0 || t.getSecond() != 0 || t.getNano() != 0) {
                offGrid++;
                first = first == null ? e.title() + " at " + t : first;
            }
        }
        if (offGrid > 0) {
            out.add(new Violation(id(), offGrid + " appointments start off a five-minute grid, first: " + first));
        }
    }
}
