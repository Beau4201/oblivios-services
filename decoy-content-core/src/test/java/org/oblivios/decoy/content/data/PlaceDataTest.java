// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Test;

class PlaceDataTest {
    private static final String UTRECHT = "GM0344";

    /** The data rows of a file of the library, read here apart from the class under test. */
    static List<String[]> rows(String file) throws IOException {
        try (InputStream in = PlaceDataTest.class.getResourceAsStream("/org/oblivios/decoy/content/data/" + file);
                BufferedReader r = new BufferedReader(new InputStreamReader(
                        file.endsWith(".gz") ? new GZIPInputStream(in) : in, StandardCharsets.UTF_8))) {
            return r.lines().filter(l -> !l.startsWith("#")).map(l -> l.split("\t", -1)).toList();
        }
    }

    private static double metres(double lat, double lon, double lat2, double lon2) {
        return Math.hypot((lat - lat2) * 111_320, (lon - lon2) * 111_320 * Math.cos(Math.toRadians(lat)));
    }

    private static String refusal(String typed) {
        return assertThrows(IllegalArgumentException.class, () -> PlaceData.require("NL", typed)).getMessage();
    }

    @Test
    void unknownCityIsRefusedWithSuggestions() {
        String message = refusal("Utrect");
        assertTrue(message.startsWith("city: unknown 'Utrect', did you mean: Utrecht, "), message);
        assertEquals(3, message.substring(message.indexOf(": ", 6) + 2).split(", ").length, message);
    }

    @Test
    void caseSpacesAndCommonNamesAreAccepted() {
        PlaceData nl = PlaceData.load("NL");
        CityRef hague = nl.city("den haag").orElseThrow();
        assertEquals("GM0518", hague.municipality());
        assertEquals("Den Haag", hague.name());
        assertEquals(hague, nl.city(" DenHaag ").orElseThrow());
        assertEquals(hague, nl.city("'s-Gravenhage").orElseThrow());
        assertEquals(UTRECHT, nl.city("UTRECHT").orElseThrow().municipality());
        assertEquals("GM0164", nl.city("Hengelo").orElseThrow().municipality());
        assertTrue(nl.city("Atlantis").isEmpty());
        assertTrue(nl.city("").isEmpty());
        assertTrue(nl.city(null).isEmpty());
    }

    @Test
    void aNameOfTwoMunicipalitiesIsRefusedWithBoth() {
        assertTrue(PlaceData.load("NL").city("Bergen").isEmpty());
        String message = refusal("Bergen");
        assertTrue(message.contains("Bergen (L.)") && message.contains("Bergen (NH.)"), message);
    }

    @Test
    void aVeryLongTypedCityIsRefusedWithoutCarryingItAll() {
        String message = refusal("x".repeat(1_000_000));
        assertTrue(message.length() < 300, "message of " + message.length());
    }

    @Test
    void aCountryWithoutPlaceDataIsRefused() {
        assertEquals("no place data for country XX",
                assertThrows(IllegalArgumentException.class, () -> PlaceData.load("XX")).getMessage());
        assertEquals("no place data for country ../NL",
                assertThrows(IllegalArgumentException.class, () -> PlaceData.load("../NL")).getMessage());
        assertEquals("city: no place data for 'utrecht' in country XX",
                assertThrows(IllegalArgumentException.class, () -> PlaceData.require("XX", "utrecht")).getMessage());
    }

    @Test
    void pointsLieInPopulatedSquares() throws IOException {
        List<String[]> squares = rows("places/NL/points.tsv.gz").stream().filter(c -> c[0].equals(UTRECHT)).toList();
        PlaceData nl = PlaceData.load("NL");
        SplittableRandom r = new SplittableRandom(5);
        Set<String> hit = new HashSet<>();
        for (int i = 0; i < 1_000; i++) {
            double[] p = nl.point(UTRECHT, r);
            String[] nearest = null;
            double best = Double.MAX_VALUE;
            for (String[] s : squares) {
                double d = metres(p[0], p[1], Double.parseDouble(s[1]), Double.parseDouble(s[2]));
                if (d < best) {
                    best = d;
                    nearest = s;
                }
            }
            assertTrue(best <= 71, "point " + p[0] + " " + p[1] + " is " + best + " m from a square centre");
            hit.add(nearest[1] + " " + nearest[2]);
        }
        assertTrue(hit.size() > 500, "points of one city lie in " + hit.size() + " squares");
    }

    @Test
    void aSquareIsDrawnByItsInhabitants() throws IOException {
        List<String[]> squares = rows("places/NL/points.tsv.gz").stream().filter(c -> c[0].equals(UTRECHT)).toList();
        double total = squares.stream().mapToLong(c -> Long.parseLong(c[3])).sum();
        double north = squares.stream().filter(c -> Double.parseDouble(c[1]) > 52.09)
                .mapToLong(c -> Long.parseLong(c[3])).sum() / total;
        PlaceData nl = PlaceData.load("NL");
        SplittableRandom r = new SplittableRandom(6);
        int drawn = 0;
        for (int i = 0; i < 20_000; i++) {
            drawn += nl.point(UTRECHT, r)[0] > 52.09 ? 1 : 0;
        }
        assertEquals(north, drawn / 20_000.0, 0.02);
    }

    @Test
    void anUnknownMunicipalityHasNoPoint() {
        assertThrows(IllegalArgumentException.class, () -> PlaceData.load("NL").point("GM9999", new SplittableRandom(1)));
    }

    @Test
    void venuesAreReadPerMunicipalityAndKind() {
        PlaceData nl = PlaceData.load("NL");
        for (Venue.VenueKind kind : Venue.VenueKind.values()) {
            List<Venue> found = nl.venues(UTRECHT, kind);
            assertFalse(found.isEmpty(), "no " + kind + " in Utrecht");
            assertTrue(found.stream().allMatch(v -> v.kind() == kind && !v.name().isBlank()
                    && metres(v.lat(), v.lon(), 52.09, 5.12) < 25_000), kind.toString());
        }
        List<String> ids = nl.venues(UTRECHT, Venue.VenueKind.CAFE).stream().map(Venue::id).toList();
        assertEquals(ids.size(), new HashSet<>(ids).size());
        assertTrue(nl.venues("GM9999", Venue.VenueKind.CAFE).isEmpty());
    }

    @Test
    void venuesHaveNoNumber() throws IOException {
        Pattern number = Pattern.compile("(\\d[\\s().+/-]*){6,}");
        List<String> found = new ArrayList<>();
        for (String[] c : rows("places/NL/venues.tsv.gz")) {
            assertEquals(5, c.length, String.join("|", c));
            if (number.matcher(c[2]).find()) {
                found.add(c[2]);
            }
        }
        assertEquals(List.of(), found);
    }

    @Test
    void everyCityHasASchoolRegion() throws IOException {
        List<String[]> cities = rows("places/NL/cities.tsv");
        assertEquals(342, cities.size());
        PlaceData nl = PlaceData.load("NL");
        for (String[] c : cities) {
            assertTrue(Set.of("noord", "midden", "zuid").contains(c[4]), String.join("|", c));
            // every municipality can be asked for by a name of its own
            String own = c[5].isEmpty() ? c[1] : c[5].split("\\|")[0];
            assertEquals(c[0], nl.city(own).orElseThrow(() -> new AssertionError("not found: " + own)).municipality());
        }
    }

    @Test
    void theVersionNamesBothFiles() {
        String version = PlaceData.load("NL").version();
        assertTrue(version.contains("CBS squares") && version.contains("OpenStreetMap"), version);
    }
}
