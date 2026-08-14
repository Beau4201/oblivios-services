package org.aegisos.duress.state;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DuressProfileStateStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void loadingMissingFileReturnsOff() {
        DuressProfileStateStore store = new DuressProfileStateStore(tempDir.resolve("state.txt"));
        assertInstanceOf(DuressProfileState.Off.class, store.load());
    }

    @Test
    void savingThenLoadingRoundTripsRegisteredState() {
        Path path = tempDir.resolve("state.txt");
        DuressProfileStateStore store = new DuressProfileStateStore(path);
        byte[] hash = {1, 2, 3};
        byte[] salt = {4, 5, 6};
        store.save(new DuressProfileState.Registered("profile-1", hash, salt));

        DuressProfileState.Registered loaded =
                assertInstanceOf(DuressProfileState.Registered.class, store.load());
        assertEquals("profile-1", loaded.profileId());
        assertArrayEquals(hash, loaded.credentialHash());
        assertArrayEquals(salt, loaded.salt());
    }

    @Test
    void neverAppendsOnlyOneLineExistsAfterMultipleToggles() throws IOException {
        Path path = tempDir.resolve("state.txt");
        DuressProfileStateStore store = new DuressProfileStateStore(path);

        store.save(new DuressProfileState.Registered("profile-1", new byte[]{1}, new byte[]{2}));
        store.save(new DuressProfileState.Off());
        store.save(new DuressProfileState.Registered("profile-2", new byte[]{3}, new byte[]{4}));

        assertEquals(1, Files.readAllLines(path).size());
    }

    @Test
    void noTempFileRemainsAfterASuccessfulSave() throws IOException {
        Path path = tempDir.resolve("state.txt");
        DuressProfileStateStore store = new DuressProfileStateStore(path);

        store.save(new DuressProfileState.Registered("profile-1", new byte[]{1}, new byte[]{2}));

        List<String> entries;
        try (Stream<Path> listing = Files.list(tempDir)) {
            entries = listing.map(p -> p.getFileName().toString()).toList();
        }
        assertEquals(List.of("state.txt"), entries);
    }

    @Test
    void loadingACorruptedStateFileFailsClosedToOff() throws IOException {
        Path path = tempDir.resolve("state.txt");
        // Bypass the store's own save() and write a garbage line directly,
        // simulating on-disk corruption.
        Files.writeString(path, "not-a-valid-line\n", StandardCharsets.UTF_8);
        DuressProfileStateStore store = new DuressProfileStateStore(path);

        assertInstanceOf(DuressProfileState.Off.class, store.load());
    }
}
