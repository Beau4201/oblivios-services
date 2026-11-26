// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class HolidayDataTest {
    private static final HolidayData NL = HolidayData.load("NL");

    @Test
    void regionsAreNoordMiddenZuid() {
        for (String region : List.of("noord", "midden", "zuid")) {
            List<SchoolHoliday> found = NL.holidays(region);
            assertTrue(found.size() >= 20, region + " has " + found.size());
            assertTrue(found.stream().allMatch(h -> h.region().equals(region) && !h.to().isBefore(h.from())), region);
            for (int i = 1; i < found.size(); i++) {
                assertTrue(found.get(i - 1).from().isBefore(found.get(i).from()), region + " " + found.get(i));
            }
        }
        assertThrows(IllegalArgumentException.class, () -> NL.holidays("oost"));
    }

    @Test
    void summerHolidayOf2026IsRead() {
        assertTrue(NL.holidays("noord").contains(
                new SchoolHoliday("Zomervakantie", "noord", LocalDate.of(2026, 7, 4), LocalDate.of(2026, 8, 16))),
                NL.holidays("noord").stream().filter(h -> h.from().getYear() == 2026).map(Object::toString)
                        .collect(Collectors.joining("\n")));
    }

    @Test
    void theFirstAndLastDayAreThoseOfTheSchoolData() {
        assertEquals(2024, NL.firstDay().getYear());
        assertEquals(2030, NL.lastDay().getYear());
    }

    @Test
    void theKeptHolidaysOfABackgroundHaveDates() {
        assertTrue(NL.kept("NL").contains("christmas-day"), NL.kept("NL").toString());
        assertTrue(NL.keptDates("kings-day").stream().anyMatch(h -> h.from().equals(LocalDate.of(2026, 4, 27))));
        assertTrue(NL.keptDates("no-such-day").isEmpty());
        assertTrue(NL.kept("TR").isEmpty());
    }

    @Test
    void aCountryWithoutHolidayDataIsRefused() {
        assertEquals("no holiday data for country XX",
                assertThrows(IllegalArgumentException.class, () -> HolidayData.load("XX")).getMessage());
    }

    @Test
    void theVersionNamesBothFiles() {
        assertTrue(NL.version().contains("2029-2030") && NL.version().contains("vacanza"), NL.version());
    }
}
