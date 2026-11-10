// SPDX-License-Identifier: Apache-2.0
package org.oblivios.duress.state;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Set;

/**
 * Loads and durably persists the device's {@link DuressProfileState}.
 *
 * <p>Not safe for concurrent {@code save()} calls from multiple threads or
 * processes against the same path: the temp-file name used during the
 * write-then-atomic-move sequence is fixed and there is no locking. This is
 * an acceptable contract for this feature's actual single-process,
 * single-writer usage, but is stated explicitly here rather than left
 * implicit.
 */
public final class DuressProfileStateStore {

    private static final Set<PosixFilePermission> OWNER_ONLY_PERMISSIONS =
            PosixFilePermissions.fromString("rw-------");

    private final Path path;

    public DuressProfileStateStore(Path path) {
        if (path.getParent() == null) {
            throw new IllegalArgumentException(
                    "path must have a parent directory: " + path);
        }
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
        } catch (IllegalArgumentException e) {
            // Fail closed: a corrupted/malformed state file must be treated
            // as "no decoy registered", not crash the caller.
            return new DuressProfileState.Off();
        }
    }

    public void save(DuressProfileState state) {
        Path tempPath = path.resolveSibling(path.getFileName() + ".tmp");
        boolean moved = false;
        try {
            Files.deleteIfExists(tempPath);
            FileAttribute<Set<PosixFilePermission>> ownerOnly =
                    PosixFilePermissions.asFileAttribute(OWNER_ONLY_PERMISSIONS);
            Files.createFile(tempPath, ownerOnly);
            Files.writeString(
                    tempPath,
                    DuressProfileStateCodec.encode(state) + "\n",
                    StandardCharsets.UTF_8,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING);
            try (FileChannel tempChannel = FileChannel.open(tempPath, StandardOpenOption.WRITE)) {
                tempChannel.force(true);
            }
            Files.move(
                    tempPath,
                    path,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            moved = true;
            // The directory fsync below happens AFTER the atomic move above
            // has already succeeded. If this step throws, the new state has
            // already been durably persisted even though save() is about to
            // report failure — a caller catching an exception from save()
            // must not assume the previous state is still in effect.
            try (FileChannel dirChannel = FileChannel.open(path.getParent(), StandardOpenOption.READ)) {
                dirChannel.force(true);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            if (!moved) {
                try {
                    Files.deleteIfExists(tempPath);
                } catch (IOException cleanupFailure) {
                    // Best-effort: don't mask the original failure, but
                    // don't let a cleanup failure crash silently either.
                    System.err.println(
                            "Failed to clean up temp duress state file " + tempPath + ": "
                                    + cleanupFailure);
                }
            }
        }
    }
}
