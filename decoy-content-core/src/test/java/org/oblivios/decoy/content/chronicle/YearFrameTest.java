// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.chronicle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.oblivios.decoy.content.chronicle.YearFrame.Season;
import org.oblivios.decoy.content.country.CountryPack;
import org.oblivios.decoy.content.country.Packs;
import org.oblivios.decoy.content.data.HolidayData;
import org.oblivios.decoy.content.data.PlaceData;

class YearFrameTest {
    private static final CountryPack PACK = Packs.load("NL").orElseThrow();
    private static final HolidayData HOLIDAYS = HolidayData.load("NL");
    private static final LocalDate FROM = LocalDate.of(2026, 1, 1);
    private static final LocalDate TO = LocalDate.of(2026, 12, 31);
    private static final Set<DayOfWeek> WEEK = EnumSet.range(DayOfWeek.MONDAY, DayOfWeek.FRIDAY);

    private static YearFrame frame(String city, Set<String> kept) {
        return YearFrame.of(PACK, HOLIDAYS, PlaceData.require("NL", city).schoolRegion(), FROM, TO, kept);
    }

    @Test
    void kingsDayIsAPublicHoliday() {
        YearFrame f = frame("utrecht", Set.of());
        LocalDate kingsDay = LocalDate.of(2026, 4, 27);
        assertTrue(f.publicHoliday(kingsDay));
        assertEquals(DayKind.HOLIDAY, f.kind(kingsDay, false, WEEK));
        assertFalse(f.publicHoliday(kingsDay.plusDays(1)));
    }

    @Test
    void summerHolidayFollowsTheRegion() {
        YearFrame north = frame("groningen", Set.of());
        YearFrame south = frame("maastricht", Set.of());
        int differ = 0;
        for (LocalDate d = LocalDate.of(2026, 7, 1); d.getMonthValue() < 9; d = d.plusDays(1)) {
            differ += north.schoolHoliday(d) != south.schoolHoliday(d) ? 1 : 0;
        }
        assertTrue(differ > 0, "both regions have the same summer");
        assertTrue(north.schoolHoliday(LocalDate.of(2026, 7, 27)));
        assertTrue(south.schoolHoliday(LocalDate.of(2026, 7, 27)));
        assertFalse(north.schoolHoliday(LocalDate.of(2026, 11, 24)));
    }

    @Test
    void weekendIsAWeekend() {
        YearFrame f = frame("utrecht", Set.of());
        assertEquals(DayKind.WEEKEND, f.kind(LocalDate.of(2026, 11, 21), false, WEEK));
        assertEquals(DayKind.WORKDAY, f.kind(LocalDate.of(2026, 11, 23), false, WEEK));
        assertEquals(DayKind.SCHOOL_DAY, f.kind(LocalDate.of(2026, 11, 23), true, WEEK));
        assertEquals(DayKind.HOLIDAY, f.kind(LocalDate.of(2026, 7, 27), true, WEEK));
        // a free weekday is neither a work day nor a weekend
        assertEquals(DayKind.ANY, f.kind(LocalDate.of(2026, 11, 25), false, Set.of(DayOfWeek.MONDAY)));
        // a shift on a Saturday is a work day
        assertEquals(DayKind.WORKDAY, f.kind(LocalDate.of(2026, 11, 21), false, Set.of(DayOfWeek.SATURDAY)));
    }

    @Test
    void keptHolidaysAreInTheYear() {
        LocalDate christmas = LocalDate.of(2026, 12, 25);
        assertEquals(Optional.of("christmas-day"), frame("utrecht", Set.of("christmas-day")).keptHoliday(christmas));
        assertEquals(Optional.empty(), frame("utrecht", Set.of("christmas-day")).keptHoliday(christmas.minusDays(3)));
        assertEquals(Optional.empty(), frame("utrecht", Set.of()).keptHoliday(christmas));
    }

    @Test
    void julyIsWinterInTheSouth() {
        YearFrame f = frame("utrecht", Set.of());
        LocalDate july = LocalDate.of(2026, 7, 10);
        assertEquals(Season.SUMMER, f.season(july, 52.0));
        assertEquals(Season.WINTER, f.season(july, -34.0));
        assertEquals(Season.WINTER, f.season(LocalDate.of(2026, 12, 1), 52.0));
        assertEquals(Season.SPRING, f.season(LocalDate.of(2026, 3, 1), 52.0));
        assertEquals(Season.AUTUMN, f.season(LocalDate.of(2026, 11, 30), 52.0));
    }
}
