// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** Language- and country-specific text lives in data files only (ADR-321); the code names no language. */
class CodeHoldsNoLanguageTest {
    private static final Path SELF = Path.of("src/test/java/org/oblivios/decoy/content/CodeHoldsNoLanguageTest.java");
    private static final Pattern LITERAL = Pattern.compile("\"(?:[^\"\\\\\\n]|\\\\.)*\"");
    private static final Pattern WORD = Pattern.compile("\\p{L}{4,}");

    private static List<Path> javaFiles(String root) throws IOException {
        try (Stream<Path> s = Files.walk(Path.of(root))) {
            return s.filter(p -> p.toString().endsWith(".java")).toList();
        }
    }

    private static Set<String> words(String text) {
        Set<String> out = new HashSet<>();
        Matcher m = WORD.matcher(text);
        while (m.find()) {
            out.add(m.group().toLowerCase(Locale.ROOT));
        }
        return out;
    }

    private static Properties read(String path) throws IOException {
        try (InputStream in = getResource("/org/oblivios/decoy/content/data/" + path)) {
            Properties p = new Properties();
            p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            return p;
        }
    }

    /** The words of a language's hand-written file and of the files a tool wrote for it. */
    private static Set<String> valueWords(String tag) throws IOException {
        List<Properties> files = new ArrayList<>(List.of(read("lang/" + tag + ".properties")));
        for (String kind : read("text/" + tag + "/index.properties").getProperty("kinds").split("\\|")) {
            files.add(read("text/" + tag + "/" + kind + ".properties"));
        }
        Set<String> out = new HashSet<>();
        files.forEach(p -> p.stringPropertyNames().forEach(k -> out.addAll(words(p.getProperty(k)))));
        return out;
    }

    private static InputStream getResource(String path) {
        return CodeHoldsNoLanguageTest.class.getResourceAsStream(path);
    }

    @Test
    void noIdentifierNamesALanguage() throws IOException {
        Pattern named = Pattern.compile("(?i)dutch");
        List<Path> hits = new ArrayList<>();
        for (String root : List.of("src/main/java", "src/test/java")) {
            for (Path p : javaFiles(root)) {
                if (!p.equals(SELF) && named.matcher(Files.readString(p)).find()) {
                    hits.add(p);
                }
            }
        }
        assertTrue(hits.isEmpty(), "files that name a language: " + hits);
    }

    @Test
    void noLanguageDataInCode() throws IOException {
        Set<String> onlyNl = valueWords("nl");
        onlyNl.removeAll(valueWords("en"));
        List<String> hits = new ArrayList<>();
        for (Path p : javaFiles("src/main/java")) {
            Matcher m = LITERAL.matcher(Files.readString(p));
            while (m.find()) {
                for (String w : words(m.group())) {
                    if (onlyNl.contains(w)) {
                        hits.add(p.getFileName() + ": " + m.group());
                    }
                }
            }
        }
        assertTrue(hits.isEmpty(), "language data in code: " + hits);
    }
}
