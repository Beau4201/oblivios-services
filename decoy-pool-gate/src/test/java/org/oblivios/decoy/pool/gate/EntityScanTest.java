// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.pool.gate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.oblivios.decoy.content.pool.PoolRead;
import org.oblivios.decoy.content.pool.PoolReader;

class EntityScanTest {
    @TempDir
    Path dir;

    // the two family names that are ordinary words, over which the Dutch round of 2026-11-26 lost conversations
    private static final EntityScan.Names NAMES = EntityScan.Names.of("nl", Set.of("Sanne"), Set.of("Lang", "Bakker", "de Jong"));

    private static List<String> findings(String text) throws IOException {
        return findingsWith(NAMES, text);
    }

    @Test
    void ordinaryWordsAreNotNames() throws IOException {
        assertEquals(List.of(), findings("Lang geleden zeg"));
        assertEquals(List.of(), findings("Bakker had er nog maar vier"));
        assertEquals(List.of(), findings("ben je al bij de bakker"));
    }

    @Test
    void aListedNameIsFound() throws IOException {
        assertEquals(List.of("message 1: the name Sanne"), findings("Sanne komt ook"));
        assertEquals(List.of("message 1: the name Bakker"), findings("ik heb Bakker gisteren gesproken"));
        assertEquals(List.of("message 1: the name De Jong"), findings("die van De Jong is er ook"));
    }

    @Test
    void theListFileIsRead() throws IOException {
        Path file = Files.writeString(dir.resolve("scan-names-nl.txt"), "# written by a tool\ngiven\tSanne\nfamily\tde Jong\n");
        EntityScan.Names names = EntityScan.Names.read(file);
        assertEquals("nl", names.language());
        assertEquals(findingsWith(names, "Sanne en die van De Jong").size(), 2);
    }

    private static List<String> findingsWith(EntityScan.Names names, String text) throws IOException {
        // a pool conversation has at least 15 messages
        String pool = "#conv id=c1 sit=weekend-plans lang=nl group=- rel=-/FRIEND age=28-35/28-35 gender=MALE/FEMALE reg=CASUAL/CASUAL"
                + " topics=friends requires=- mix=-\n0 12:00 A " + text + "\n" + "0 12:01 B ja is goed hoor\n".repeat(14);
        PoolRead read = PoolReader.read(new StringReader(pool));
        assertEquals(List.of(), read.errors());
        return EntityScan.findings(read.conversations().get(0), names);
    }

    @Test
    void aListFileThatIsEmptyOrMisnamedOrMalformedIsRefused() throws IOException {
        for (String[] bad : new String[][] {{"scan-names-nl.txt", "# nothing\n"}, {"names.txt", "given\tSanne\n"},
                {"scan-names-nl.txt", "Sanne\n"}, {"scan-names-nl.txt", "other\tSanne\n"}}) {
            Path file = Files.writeString(dir.resolve(bad[0]), bad[1]);
            assertThrows(IOException.class, () -> EntityScan.Names.read(file), bad[0] + " " + bad[1]);
        }
    }
}
