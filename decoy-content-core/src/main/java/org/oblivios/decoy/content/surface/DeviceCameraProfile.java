// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.surface;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The output shape of this device's camera, read from sample photos by {@code tools/decoy-content/jpeg_shape.py}
 * (Task 5): stored size, the JPEG quantisation tables, the file name pattern and whether the camera writes GPS.
 * {@code fileNameTemplate} is a {@link DateTimeFormatter} pattern that produces names matching
 * {@code fileNameRegex}; the constructor refuses a template that does not.
 */
public record DeviceCameraProfile(int width, int height, List<int[]> dqt, String fileNameRegex, String fileNameTemplate,
        boolean gps) {
    public DeviceCameraProfile {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("size " + width + "x" + height);
        }
        if (dqt.isEmpty()) {
            throw new IllegalArgumentException("no quantisation table");
        }
        for (int[] table : dqt) {
            if (table.length != 64) {
                throw new IllegalArgumentException("a quantisation table has 64 values, not " + table.length);
            }
        }
        dqt = dqt.stream().map(int[]::clone).toList();
        String sample = DateTimeFormatter.ofPattern(fileNameTemplate, Locale.ROOT).withZone(ZoneOffset.UTC)
                .format(Instant.parse("2023-05-17T10:20:30.123Z"));
        if (!Pattern.matches(fileNameRegex, sample)) {
            throw new IllegalArgumentException("template " + fileNameTemplate + " gives " + sample + ", which the regex refuses");
        }
    }

    public String fileName(Instant at, ZoneId zone) {
        return DateTimeFormatter.ofPattern(fileNameTemplate, Locale.ROOT).withZone(zone).format(at);
    }

    /** A stable id of the shape, so an item can say which profile renders it. */
    public String id() {
        return "cam-" + Integer.toHexString(Objects.hash(width, height, Arrays.deepHashCode(dqt.toArray())));
    }
}
