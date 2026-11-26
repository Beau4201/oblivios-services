// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;
import org.junit.jupiter.api.Test;

class DataSetTest {
    @Test
    void versionsListTheFilesThatWereRead() {
        DataSet data = DataSet.of("NL");
        assertEquals(Map.of(), data.versions());
        data.names("NL");
        data.names("NL");
        assertEquals(Map.of("names/NL", NameData.load("NL").version()), data.versions());
    }

    @Test
    void oneSetKeepsOneCoupleChoice() {
        DataSet data = DataSet.of("NL");
        assertEquals(data.family(), data.family());
    }

    @Test
    void aCountryWithoutAPackIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> DataSet.of("ZZ"));
    }
}
