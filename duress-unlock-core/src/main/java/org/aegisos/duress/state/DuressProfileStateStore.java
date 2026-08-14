package org.aegisos.duress.state;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.List;

public final class DuressProfileStateStore {

    private final Path path;

    public DuressProfileStateStore(Path path) {
        this.path = path;
    }

    public DuressProfileState load() {
        if (!Files.exists(path)) {
            return new DuressProfileState.Off();
        }
        try {
            List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
            if (lines.isEmpty()) {
                return new DuressProfileState.Off();
            }
            return DuressProfileStateCodec.decode(lines.get(0));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void save(DuressProfileState state) {
        Path tempPath = path.resolveSibling(path.getFileName() + ".tmp");
        try {
            Files.writeString(
                    tempPath,
                    DuressProfileStateCodec.encode(state) + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING);
            Files.move(
                    tempPath,
                    path,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
