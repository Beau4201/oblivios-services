// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.lang;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.oblivios.decoy.content.persona.PersonaProfile;
import org.oblivios.decoy.content.Persona;
import org.oblivios.decoy.content.PersonaInputs;
import org.oblivios.decoy.content.Seed;
import org.oblivios.decoy.content.country.CountryPack;
import org.oblivios.decoy.content.country.Packs;
import org.oblivios.decoy.content.story.MonthCount;
import org.oblivios.decoy.content.story.Timeline;
import org.oblivios.decoy.content.story.VolumeCounts;
import org.oblivios.decoy.content.story.VolumeModel;
import org.oblivios.decoy.content.surface.People;
import org.oblivios.decoy.content.surface.PeopleSurface;
import org.oblivios.decoy.content.surface.SmsMessage;
import org.oblivios.decoy.content.surface.SmsThread;

class LanguagePackTest {
    private static final Instant NOW = Instant.parse("2026-11-25T12:00:00Z");
    private static final Pattern SLOT = Pattern.compile("\\{([^}]*)}");
    private static final Set<String> SLOTS = Set.of("name", "place", "day", "time", "n", "code", "ref", "customer");

    private static Properties raw(String tag) throws IOException {
        try (InputStream in = LanguagePack.class.getResourceAsStream("/org/oblivios/decoy/content/data/lang/" + tag + ".properties")) {
            Properties p = new Properties();
            p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            return p;
        }
    }

    /** A template as a pattern: its text quoted, each slot any non-empty text. */
    private static Pattern pattern(String template) {
        StringBuilder sb = new StringBuilder();
        Matcher m = SLOT.matcher(template);
        int at = 0;
        while (m.find()) {
            sb.append(Pattern.quote(template.substring(at, m.start()))).append(".+?");
            at = m.end();
        }
        sb.append(Pattern.quote(template.substring(at)));
        return Pattern.compile(sb.toString());
    }

    private static boolean matchesAny(String text, List<String> templates) {
        return templates.stream().anyMatch(t -> pattern(t).matcher(text).matches());
    }

    private static People people(String personaTag, long n) {
        Persona p = org.oblivios.decoy.content.TestSetup.derive(Seed.of(n, 1),
                PersonaProfile.open(new PersonaInputs("Sam", "Example", "Utrecht", "NL", ZoneId.of("Europe/Amsterdam"), Locale.forLanguageTag(personaTag))), 2026);
        List<MonthCount> months = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            months.add(new MonthCount(YearMonth.of(2026, 10).minusMonths(11 - i), 60, 10, 8, 1_000_000L));
        }
        CountryPack nl = Packs.load("NL").orElseThrow();
        Timeline t = Timeline.generate(p, VolumeModel.fromCounts(new VolumeCounts(months), org.oblivios.decoy.content.Draws.of(Seed.of(n, 2))), nl, NOW, org.oblivios.decoy.content.Draws.of(Seed.of(n, 3)));
        return PeopleSurface.generate(p, t, nl, org.oblivios.decoy.content.Draws.of(Seed.of(n, 4)));
    }

    @Test
    void languageWithoutDataIsRefused() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> LanguagePack.of("de"));
        assertEquals("no language data for de", e.getMessage());
    }

    @Test
    void availableLanguagesAreRead() {
        assertEquals(Set.of("en", "nl"), LanguagePack.available());
        for (String tag : LanguagePack.available()) {
            assertEquals(tag, LanguagePack.of(tag).tag());
        }
    }

    @Test
    void aRegionalTagUsesItsLanguage() {
        assertEquals("nl", LanguagePack.of("nl-NL").tag());
    }

    @Test
    void aKeyInTwoFilesIsRefused() {
        Properties into = new Properties();
        into.setProperty("weekdays", "a");
        Properties add = new Properties();
        add.setProperty("weekdays", "b");
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> LanguagePack.merge(into, add, "text/en/weekdays.properties"));
        assertEquals("text/en/weekdays.properties: the key weekdays is in two files", e.getMessage());
    }

    @Test
    void everyLanguageHasItsWeekdaysAndStopWords() {
        for (String tag : LanguagePack.available()) {
            assertTrue(LanguagePack.of(tag).has("weekdays") && LanguagePack.of(tag).has("stopwords"), tag);
        }
    }

    @Test
    void missingKeyThrows() {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> LanguagePack.of("en").text("no.such.key"));
        assertTrue(e.getMessage().contains("no.such.key"), e.getMessage());
    }

    @Test
    void everyKeyExistsInEveryLanguage() throws IOException {
        assertEquals(raw("nl").stringPropertyNames(), raw("en").stringPropertyNames());
    }

    @Test
    void noTemplateHasAnUnknownSlot() throws IOException {
        for (String tag : LanguagePack.available()) {
            Properties p = raw(tag);
            for (String key : p.stringPropertyNames()) {
                Matcher m = SLOT.matcher(p.getProperty(key));
                while (m.find()) {
                    assertTrue(SLOTS.contains(m.group(1)), tag + " " + key + " has slot {" + m.group(1) + "}");
                }
            }
        }
    }

    @Test
    void weekdaysStartOnMonday() {
        LanguagePack en = LanguagePack.of("en");
        assertEquals(en.list("weekdays").get(0), en.weekday(DayOfWeek.MONDAY));
        assertEquals(en.list("weekdays").get(6), en.weekday(DayOfWeek.SUNDAY));
        assertEquals(7, LanguagePack.of("nl").list("weekdays").size());
    }

    @Test
    void formatFillsTheSlots() {
        String s = LanguagePack.of("en").format("calendar.birthday", Map.of("name", "Ada"));
        assertTrue(s.contains("Ada") && !s.contains("{"), s);
    }

    @Test
    void serviceTextsFollowTheCountryLanguage() {
        LanguagePack nl = LanguagePack.of("nl");
        List<String> service = new ArrayList<>();
        for (String k : List.of("sms.service.code", "sms.service.BANK", "sms.service.OPERATOR", "sms.service.PARCEL",
                "sms.service.SHOP", "sms.service.GLOBAL")) {
            service.add(nl.text(k));
        }
        int seen = 0;
        for (long n = 0; n < 3; n++) {
            for (SmsThread t : people("en-US", n).threads()) {
                if (t.senderKind() == null) {
                    continue;
                }
                for (SmsMessage m : t.messages()) {
                    assertTrue(matchesAny(m.body(), service), "not a service text of the country: " + m.body());
                    seen++;
                }
            }
        }
        assertTrue(seen > 0);
    }

}
