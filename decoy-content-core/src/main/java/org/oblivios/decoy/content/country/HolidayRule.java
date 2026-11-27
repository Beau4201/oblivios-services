// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.country;

import java.time.DayOfWeek;
import java.time.LocalDate;

/**
 * One public holiday of a country pack, parsed from a rule of its data file: {@code fixed MM-DD},
 * {@code easter <offset>} (days from Easter Sunday) or {@code fixed-sunday-back MM-DD} (a fixed date that moves to
 * the Saturday before when it falls on a Sunday). A rule carries no name: the language files give it by {@link #key}.
 */
public record HolidayRule(Kind kind, int month, int day, int offset) {

    public enum Kind { FIXED, EASTER, FIXED_SUNDAY_BACK }

    /** The key of the holiday's name: {@code holiday.MM-DD}, or {@code holiday.easter.<offset>}. */
    public String key() {
        return kind == Kind.EASTER ? "holiday.easter." + offset : String.format("holiday.%02d-%02d", month, day);
    }

    public static HolidayRule parse(String rule) {
        String[] parts = rule.trim().split("\\s+");
        if (parts.length != 2) {
            throw new IllegalStateException("holiday rule is not a kind and a date: '" + rule + "'");
        }
        try {
            switch (parts[0]) {
                case "fixed", "fixed-sunday-back" -> {
                    String[] md = parts[1].split("-");
                    if (md.length != 2) {
                        throw new IllegalStateException("holiday rule date is not MM-DD: '" + rule + "'");
                    }
                    int month = Integer.parseInt(md[0]);
                    int day = Integer.parseInt(md[1]);
                    LocalDate.of(2000, month, day); // a leap year: validates the day of the month
                    Kind k = parts[0].equals("fixed") ? Kind.FIXED : Kind.FIXED_SUNDAY_BACK;
                    return new HolidayRule(k, month, day, 0);
                }
                case "easter" -> {
                    return new HolidayRule(Kind.EASTER, 0, 0, Integer.parseInt(parts[1]));
                }
                default -> throw new IllegalStateException("unknown holiday rule '" + parts[0] + "' in '" + rule + "'");
            }
        } catch (NumberFormatException | java.time.DateTimeException e) {
            throw new IllegalStateException("bad holiday rule '" + rule + "': " + e.getMessage(), e);
        }
    }

    public LocalDate on(int year) {
        return switch (kind) {
            case FIXED -> LocalDate.of(year, month, day);
            case EASTER -> easter(year).plusDays(offset);
            case FIXED_SUNDAY_BACK -> {
                LocalDate d = LocalDate.of(year, month, day);
                yield d.getDayOfWeek() == DayOfWeek.SUNDAY ? d.minusDays(1) : d;
            }
        };
    }

    /** Easter Sunday by the anonymous Gregorian algorithm. */
    static LocalDate easter(int y) {
        int a = y % 19, b = y / 100, c = y % 100, d = b / 4, e = b % 4, f = (b + 8) / 25, g = (b - f + 1) / 3;
        int h = (19 * a + b - d - g + 15) % 30, i = c / 4, k = c % 4, l = (32 + 2 * e + 2 * i - h - k) % 7;
        int m = (a + 11 * h + 22 * l) / 451;
        int month = (h + l - 7 * m + 114) / 31;
        int day = ((h + l - 7 * m + 114) % 31) + 1;
        return LocalDate.of(y, month, day);
    }
}
