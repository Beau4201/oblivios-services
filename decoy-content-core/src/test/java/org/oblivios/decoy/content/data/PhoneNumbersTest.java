// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;
import org.oblivios.decoy.content.country.PhoneNumber;
import org.oblivios.decoy.content.country.PhoneNumber.NumberType;

class PhoneNumbersTest {
    private static void drawValid(String country, int n, SplittableRandom r) {
        PhoneNumbers numbers = PhoneNumbers.of(country);
        for (int i = 0; i < n; i++) {
            PhoneNumber p = numbers.mobile(r);
            assertEquals(NumberType.MOBILE, p.type());
            assertTrue(numbers.valid(p), country + ": " + p.e164());
            assertFalse(numbers.never(p.e164()), country + ": " + p.e164());
        }
    }

    @Test
    void mobilesAreValidAndNeverNever() {
        SplittableRandom r = new SplittableRandom(1);
        drawValid("NL", 10_000, r);
        for (String country : List.of("TR", "MA", "PL", "DE")) {
            drawValid(country, 1_000, r);
        }
        // a pattern that overlaps a never pattern: the overlapping numbers are drawn again
        PhoneNumbers overlapping = PhoneNumbers.parse("ZZ", "country.code=99\nnational.prefix=0\nmobile.pattern=6\\\\d\n"
                + "mobile.lengths=2\nmobile.draw.length=2\nnever.patterns=6[0-8]\nversion=test\n");
        for (int i = 0; i < 200; i++) {
            assertEquals("+9969", overlapping.mobile(r).e164());
        }
    }

    @Test
    void mobilesAreSubscriberNumbers() {
        PhoneNumbers nl = PhoneNumbers.of("NL");
        SplittableRandom r = new SplittableRandom(2);
        for (int i = 0; i < 2_000; i++) {
            String n = nl.mobile(r).e164();
            assertTrue(n.startsWith("+316") && n.length() == 12, n);
        }
    }

    @Test
    void aPatternThatIsAllNeverIsRefused() {
        PhoneNumbers all = PhoneNumbers.parse("ZZ", "country.code=99\nnational.prefix=\nmobile.pattern=6\\\\d\n"
                + "mobile.lengths=2\nmobile.draw.length=2\nnever.patterns=6\\\\d\nversion=test\n");
        assertThrows(IllegalStateException.class, () -> all.mobile(new SplittableRandom(3)));
    }

    @Test
    void landlineHasTheAreaCodeOfItsPlace() {
        PhoneNumbers nl = PhoneNumbers.of("NL");
        PlaceData places = PlaceData.load("NL");
        SplittableRandom r = new SplittableRandom(4);
        for (String[] want : new String[][] {{"Utrecht", "+3130"}, {"Amsterdam", "+3120"}, {"Rotterdam", "+3110"}}) {
            String municipality = places.city(want[0]).orElseThrow().municipality();
            for (int i = 0; i < 100; i++) {
                PhoneNumber p = nl.landline(municipality, r);
                assertEquals(NumberType.FIXED, p.type());
                assertTrue(p.e164().startsWith(want[1]) && nl.valid(p), want[0] + ": " + p.e164());
            }
        }
        for (CityRef city : places.municipalities()) {
            PhoneNumber p = nl.landline(city.municipality(), r);
            assertTrue(nl.valid(p), city.name() + ": " + p.e164());
        }
    }

    @Test
    void neutralCountryHasNoNumbers() {
        for (String country : new String[] {"XX", "nl", "../NL", "", null}) {
            assertThrows(IllegalArgumentException.class, () -> PhoneNumbers.of(country), String.valueOf(country));
        }
    }

    @Test
    void storedFormIsNationalOrInternational() {
        PhoneNumbers nl = PhoneNumbers.of("NL");
        PhoneNumber p = new PhoneNumber("+31612345678", NumberType.MOBILE);
        assertEquals("0612345678", nl.stored(p, false));
        assertEquals("+31612345678", nl.stored(p, true));
    }

    @Test
    void aNumberOfAnotherTypeOrCountryIsNotValid() {
        PhoneNumbers nl = PhoneNumbers.of("NL");
        assertFalse(nl.valid(new PhoneNumber("+31612345678", NumberType.FIXED)));
        assertFalse(nl.valid(new PhoneNumber("+32612345678", NumberType.MOBILE)));
        assertFalse(nl.valid(new PhoneNumber("+318001234567", NumberType.MOBILE)));
        assertTrue(nl.never("+318001234"));
    }
}
