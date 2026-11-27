// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.lang;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * No text that a phone shows is written in the code (ADR-336). The scan covers the packages that make the content of a
 * phone ({@code surface}, {@code story}, {@code world}). Left out are what never reaches a phone: comments, diagnostics, the name of
 * a draw stream or of a language key, data keys, package names and anything without two letters in a row.
 */
class CodeHoldsNoTextTest {
    private static final Path MAIN = Path.of("src/main/java/org/oblivios/decoy/content");
    private static final Pattern COMMENT = Pattern.compile("(?s)/\\*.*?\\*/|(?m)^\\s*//[^\\n]*|(?<=[;{}]) //[^\\n]*");
    private static final String QUOTED = "\"(?:[^\"\\\\\\n]|\\\\.)*\"";
    /** A call whose string arguments are diagnostics, a stream's name or a language key. */
    private static final Pattern NOT_SHOWN = Pattern.compile(
            "(?:throw new \\w+|warnings\\.add|\\.stream|\\.text|\\.list|\\.has|\\.format)\\s*\\((?:\\s*\\+?\\s*"
                    + QUOTED + ")+");
    private static final Pattern LITERAL = Pattern.compile(QUOTED);
    private static final Pattern TWO_LETTERS = Pattern.compile("\\p{L}{2}");
    private static final Pattern KEY = Pattern.compile("[a-z]+(\\.[A-Za-z0-9_]+)+\\.?|[a-z]+\\.");

    /** Literals that name a thing for the code and are no text of their own. */
    private static final Set<String> NAMES = Set.of(
            // slots of a language template, record fields, settings keys and values, default-app roles
            "name", "place", "code", "ref", "customer", "display_size", "font_scale", "browser", "dialer", "sms", "home",
            "default", "off", "en-US", "trip",
            // internal ids, prefixes of a media source, roll-forward event kinds, mail template parts
            "cam-", "appstate:", "boardingpass:", "doc:receipt", "doc:scan", "doc:ticket", "fill:", "saved:", "gps",
            "CALL", "IN", "OUT", "SMS", ".body", ".subject",
            // the world's ids, the parts of its streams' names, a component named by a null check, parts of diagnostics
            "origin", "self", "work", "family", "pet", ".birth", ".label", ".percent", "addressed", "background", "given",
            "phone", "used", " born ", ": no family name beside one", ": no free given name for ",
            // formats, file extensions and an alphabet
            "https://", "dd-MMM-yyyy HH:mm:ss Z", "yyyyMMdd", ".jpg", ".jpeg", ".png", ".webp", ".pdf",
            "abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789",
            // the later parts of diagnostics that no phone shows
            " gives ", ", which the regex refuses", " has no sender: no mail can be created",
            " chosen photos exceed the camera volume of the story and are not used",
            " lies outside the story: no media for it", " months of counts: the defaults of a typical used phone are used",
            ", below ", ": the camera default is used",
            "The mail server ignores the date of an appended message, so a mailbox of old mail ",
            "cannot be built here: every message would carry today's date, which is a tell. Use another address, or skip the mailbox step.");

    /** Texts still written in the code, as {@code file#literal}. The list only shrinks; after the views it is empty. */
    static final Set<String> HAND_WRITTEN = Set.of(
            "BrowserSurface.java#consent", "BrowserSurface.java#lang",
            "DeviceSurface.java#Default alarm", "DeviceSurface.java#Default notification",
            "DeviceSurface.java#Default ringtone",
            "MediaSurface.java#'Screenshot_'yyyyMMdd-HHmmss'.png'", "MediaSurface.java#-edited", "MediaSurface.java#IMG",
            "MediaSurface.java#Receipt", "MediaSurface.java#Scan_", "MediaSurface.java#Ticket_",
            "MediaSurface.java#download", "MediaSurface.java#image", "MediaSurface.java#images",
            "MediaSurface.java#photo", "MediaSurface.java#unnamed", "MediaSurface.java#wallpaper",
            "NetworkSurface.java#Car", "NetworkSurface.java#Earbuds", "NetworkSurface.java#Headphones",
            "NetworkSurface.java#Speaker", "NetworkSurface.java#WORK-####", "NetworkSurface.java#Watch",
            "TracesSurface.java#Missed call", "TracesSurface.java#New activity", "TracesSurface.java#New message",
            "TracesSurface.java#Reminder", "TracesSurface.java#Update available");

    private static Stream<Path> walk(String name) {
        try {
            return Files.walk(MAIN.resolve(name));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Set<String> found() {
        Set<String> out = new TreeSet<>();
        try (Stream<Path> files = Stream.of("surface", "story", "world").flatMap(CodeHoldsNoTextTest::walk)) {
            for (Path p : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String code = NOT_SHOWN.matcher(COMMENT.matcher(Files.readString(p)).replaceAll("")).replaceAll("(");
                Matcher m = LITERAL.matcher(code);
                while (m.find()) {
                    String text = m.group().substring(1, m.group().length() - 1);
                    if (TWO_LETTERS.matcher(text.replaceAll("\\\\.", " ")).find() && !KEY.matcher(text).matches()
                            && !NAMES.contains(text)) {
                        out.add(p.getFileName() + "#" + text);
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }

    @Test
    void noTextInCode() {
        Set<String> found = found();
        found.removeAll(HAND_WRITTEN);
        assertTrue(found.isEmpty(), "text in the code:\n" + String.join("\n", found));
    }

    @Test
    void handWrittenTextsStillExist() {
        Set<String> gone = new TreeSet<>(HAND_WRITTEN);
        gone.removeAll(found());
        assertTrue(gone.isEmpty(), "listed as hand-written, but gone:\n" + String.join("\n", gone));
    }

    @Test
    void theScanSeesALiteralOfAView() {
        assertTrue(List.copyOf(found()).stream().anyMatch(f -> f.equals("TracesSurface.java#Missed call")), found().toString());
    }
}
