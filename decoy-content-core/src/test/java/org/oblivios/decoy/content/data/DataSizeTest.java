// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.data;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class DataSizeTest {
    /** The whole data of the library, which holds one country: what an image would carry. */
    @Test
    void dataOfOneCountryStaysUnder16MB() throws IOException, URISyntaxException {
        Path data = Path.of(DataSizeTest.class.getResource("/org/oblivios/decoy/content/data/country/NL.properties").toURI())
                .getParent().getParent();
        try (Stream<Path> files = Files.walk(data)) {
            long bytes = files.filter(Files::isRegularFile).mapToLong(f -> f.toFile().length()).sum();
            assertTrue(bytes > 1_000_000 && bytes < 16L * 1024 * 1024, bytes + " bytes under " + data);
        }
    }
}
