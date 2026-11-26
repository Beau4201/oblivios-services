// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.data;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/** The data files of the library: three header lines, then rows of cells between tabs. */
final class Tables {
    private static final String DIR = "/org/oblivios/decoy/content/data/";
    private static final Pattern COUNTRY = Pattern.compile("[A-Z]{2}");

    private Tables() {}

    /** True for a country code that has this file; the code is checked before it becomes part of a path. */
    static boolean has(String country, String folder, String file) {
        return country != null && COUNTRY.matcher(country).matches()
                && Tables.class.getResource(DIR + folder + "/" + file.replace("{cc}", country)) != null;
    }

    static Reader open(String path) {
        try {
            InputStream in = Tables.class.getResourceAsStream(DIR + path);
            if (in == null) {
                throw new IllegalStateException("data file " + path + " is missing");
            }
            return new InputStreamReader(path.endsWith(".gz") ? new GZIPInputStream(in) : in, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Gives each row of {@code cells} cells to {@code row} and returns the version line. A missing header line or a
     * row that is not what it should be throws {@link IllegalStateException} naming the file and the line.
     */
    static String read(String name, Reader reader, int cells, Consumer<String[]> row) {
        try (BufferedReader in = new BufferedReader(reader)) {
            String version = null;
            for (String start : List.of("# source: ", "# licence: ", "# version: ")) {
                String line = in.readLine();
                if (line == null || !line.startsWith(start)) {
                    throw new IllegalStateException(name + ": the line '" + start + "...' is missing");
                }
                version = line.substring(start.length());
            }
            int number = 3;
            for (String line = in.readLine(); line != null; line = in.readLine()) {
                number++;
                if (line.startsWith("#")) {
                    continue;
                }
                try {
                    String[] found = line.split("\t", -1);
                    if (found.length != cells) {
                        throw new IllegalArgumentException("not " + cells + " cells");
                    }
                    row.accept(found);
                } catch (IllegalArgumentException | java.time.DateTimeException e) {
                    throw new IllegalStateException(name + " line " + number + ": " + e.getMessage(), e);
                }
            }
            return version;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
