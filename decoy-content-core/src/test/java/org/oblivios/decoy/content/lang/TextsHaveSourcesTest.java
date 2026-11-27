// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.lang;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Test;

/**
 * Every text and datum of the data files names its source (ADR-336). A key has one when a comment block with a
 * {@code # source:} line stands directly above it, or when its file is written by a tool and starts with source,
 * licence and version lines (not under {@code text/}, where each key names its own place in the source).
 */
class TextsHaveSourcesTest {
    private static final Path DATA = Path.of("src/main/resources/org/oblivios/decoy/content/data");

    /** Keys that are still written by hand. The list only shrinks; after the views are rebuilt it is empty. */
    static final Set<String> HAND_WRITTEN = handWritten();

    private static Set<String> handWritten() {
        Set<String> out = new TreeSet<>();
        List<String> language = new ArrayList<>(List.of("browser.paths", "calendar.appointments", "calendar.birthday",
                "calendar.lunch", "calendar.notes", "device.keyboard.layouts", "device.keyboard.words",
                "device.name", "holiday.generic", "mail.closing", "mail.greeting", "place.cafe", "place.home",
                "place.supermarket", "place.work", "presumes.CAR", "presumes.CAT", "presumes.DOG",
                "presumes.SCHOOL_CHILDREN", "presumes.WORKS", "register.STREET.words", "sms.service.code",
                "travel.centre", "travel.hotel", "travel.station", "wifi.guest"));
        for (String sender : List.of("BANK", "OPERATOR", "PARCEL", "SHOP", "GLOBAL")) {
            language.add("sms.service." + sender);
            for (int n = 1; n <= 3; n++) {
                language.add("mail." + sender + "." + n + ".subject");
                language.add("mail." + sender + "." + n + ".body");
            }
        }
        for (String tag : List.of("en", "nl")) {
            language.forEach(k -> out.add("lang/" + tag + ".properties#" + k));
        }
        for (String k : List.of("senders.BANK", "senders.PARCEL", "senders.OPERATOR", "senders.SHOP",
                "senders.SHOP.online", "supermarkets", "holidays", "wifi", "amount", "mms.groups", "sites.LOCAL",
                "trips.domestic")) {
            out.add("country/NL.properties#" + k);
        }
        for (String k : List.of("senders.GLOBAL", "senders.SHOP.online", "supermarkets", "holidays", "wifi", "amount",
                "mms.groups", "sites.GLOBAL", "trips.abroad")) {
            out.add("country/XX.properties#" + k);
        }
        for (String k : List.of("sites", "notes", "wifi.work", "prefixes", "notifications", "bluetooth")) {
            out.add("check/signature-allow.properties#" + k);
        }
        return out;
    }

    /** Keys that hold no text a view shows or a rule reads: which files exist, their credits, a pack's own codes. */
    private static boolean structural(String file, String key) {
        return file.endsWith("index.properties") || file.equals("attribution.properties") || key.equals("version")
                || file.startsWith("country/") && (key.equals("code") || key.equals("language"));
    }

    private static Map<String, List<String>> files() {
        Map<String, List<String>> out = new HashMap<>();
        try (Stream<Path> paths = Files.walk(DATA)) {
            for (Path p : paths.filter(Files::isRegularFile).toList()) {
                try (InputStream raw = Files.newInputStream(p);
                        InputStream in = p.toString().endsWith(".gz") ? new GZIPInputStream(raw) : raw) {
                    BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
                    out.put(DATA.relativize(p).toString().replace('\\', '/'), r.lines().toList());
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }

    private static boolean hasHead(List<String> lines) {
        return lines.size() > 2 && lines.get(0).startsWith("# source: ") && !lines.get(0).startsWith("# source: none")
                && lines.get(1).startsWith("# licence: ") && lines.get(2).startsWith("# version: ");
    }

    /** {@code file#key} of every key without a source, and {@code file} of every table without a head. */
    private static Set<String> unsourced() {
        Set<String> out = new TreeSet<>();
        files().forEach((file, lines) -> {
            if (!file.endsWith(".properties")) {
                if (!hasHead(lines)) {
                    out.add(file);
                }
                return;
            }
            boolean whole = hasHead(lines) && !file.startsWith("text/");
            boolean sourced = false;
            boolean continued = false;
            boolean inComment = false;
            // under text/ the head names the file's source; it is no key's own place in that source
            for (String line : file.startsWith("text/") && hasHead(lines) ? lines.subList(3, lines.size()) : lines) {
                if (continued) {
                    continued = line.endsWith("\\");
                    continue;
                }
                if (line.startsWith("#")) {
                    sourced = inComment && sourced || line.startsWith("# source: ") && !line.startsWith("# source: none");
                    inComment = true;
                    continue;
                }
                inComment = false;
                if (!line.isBlank()) {
                    String key = line.substring(0, Math.max(line.indexOf('='), 0)).trim();
                    if (!whole && !sourced && !structural(file, key)) {
                        out.add(file + "#" + key);
                    }
                    continued = line.endsWith("\\");
                }
                sourced = false;
            }
        });
        return out;
    }

    @Test
    void everyKeyHasASource() {
        Set<String> missing = unsourced();
        missing.removeAll(HAND_WRITTEN);
        assertTrue(missing.isEmpty(), "without a source:\n" + String.join("\n", missing));
    }

    @Test
    void handWrittenKeysStillExist() {
        Set<String> gone = new TreeSet<>(HAND_WRITTEN);
        gone.removeAll(unsourced());
        assertTrue(gone.isEmpty(), "listed as hand-written, but sourced or gone:\n" + String.join("\n", gone));
    }

    @Test
    void noKeyInTwoFiles() {
        for (String tag : LanguagePack.available()) {
            Map<String, String> seen = new HashMap<>();
            files().forEach((file, lines) -> {
                if (file.equals("lang/" + tag + ".properties")
                        || file.startsWith("text/" + tag + "/") && !file.endsWith("index.properties")) {
                    lines.stream().filter(l -> l.matches("[A-Za-z][A-Za-z0-9._-]*=.*")).forEach(l -> {
                        String key = l.substring(0, l.indexOf('='));
                        String before = seen.put(key, file);
                        assertEquals(null, before, key + " stands in " + before + " and in " + file);
                    });
                }
            });
        }
    }
}
