// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.check.rules;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.oblivios.decoy.content.Persona;
import org.oblivios.decoy.content.check.Checks;
import org.oblivios.decoy.content.check.ExaminerRule;
import org.oblivios.decoy.content.check.PhoneViews;
import org.oblivios.decoy.content.check.Violation;
import org.oblivios.decoy.content.country.CountryPack;
import org.oblivios.decoy.content.surface.CalEvent;
import org.oblivios.decoy.content.surface.MailMessage;
import org.oblivios.decoy.content.surface.MediaItem;
import org.oblivios.decoy.content.surface.Note;
import org.oblivios.decoy.content.surface.SmsThread;

/**
 * {@code M-RAW}: a message body, a thread or calendar title, a note, a mail or a file name holds a media marker of the
 * pool ({@code [photo]}, {@code [video]}, {@code [link]}, {@code [sticker]}, {@code [voice ...]}) or a slot brace.
 * One violation per text.
 */
public final class MRaw implements ExaminerRule {
    private static final Pattern RAW = Pattern.compile("\\[(?:photo|video|link|sticker|voice[^\\]]*)]|[{}]");

    @Override
    public String id() {
        return "M-RAW";
    }

    @Override
    public void check(PhoneViews v, Persona persona, CountryPack pack, Instant now, List<Violation> out) {
        List<String> texts = new ArrayList<>();
        for (SmsThread t : v.people().threads()) {
            texts.add(t.title());
            t.messages().forEach(m -> texts.add(m.body()));
        }
        v.calendar().events().stream().map(CalEvent::title).forEach(texts::add);
        v.calendar().notes().stream().map(Note::text).forEach(texts::add);
        for (MailMessage m : v.mail().messages()) {
            texts.add(m.subject());
            texts.add(m.body());
        }
        v.media().items().stream().map(MediaItem::name).forEach(texts::add);
        for (String text : texts) {
            if (text != null && RAW.matcher(text).find()) {
                out.add(new Violation(id(), "raw pool text: " + Checks.shown(text)));
            }
        }
    }
}
