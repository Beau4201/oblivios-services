package org.aegisos.guardian.audit;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.aegisos.guardian.evidence.GuardianEvidenceRetentionPeriod;

/**
 * Append-only, reboot-persistent {@link GuardianAuditStore} backed by one
 * JSON-lines file. Pure Java (no Android dependency) so it stays testable
 * with plain JUnit against a temp-directory file, matching guardian-core's
 * existing testability property (ADR-013). Callers on Android pass a path
 * under Context#getFilesDir(), which Android already keeps private to
 * Guardian's own app UID -- that sandboxing is the integrity property this
 * store relies on, not cryptographic tamper-evidence.
 */
public final class FileGuardianAuditStore implements GuardianAuditStore {
    private final Path path;
    private final Map<String, GuardianAuditEntry> entries = new LinkedHashMap<>();
    private GuardianEvidenceRetentionPeriod retentionPeriod = GuardianEvidenceRetentionPeriod.THIRTY_DAYS;

    public FileGuardianAuditStore(Path path) {
        this.path = Objects.requireNonNull(path, "path");
        load();
    }

    @Override
    public synchronized GuardianAuditEntry record(GuardianAuditEntry entry) {
        entries.put(entry.auditId(), entry);
        append(entry);
        return entry;
    }

    @Override
    public synchronized Optional<GuardianAuditEntry> find(String auditId) {
        return Optional.ofNullable(entries.get(auditId));
    }

    @Override
    public synchronized void setRetentionPeriod(GuardianEvidenceRetentionPeriod period) {
        this.retentionPeriod = Objects.requireNonNull(period, "period");
    }

    @Override
    public synchronized GuardianEvidenceRetentionPeriod retentionPeriod() {
        return retentionPeriod;
    }

    @Override
    public synchronized void applyRetention(Instant now) {
        List<String> expired = entries.values().stream()
            .filter(entry -> !retentionPeriod.isWithinWindow(entry.recordedAt(), now))
            .map(GuardianAuditEntry::auditId)
            .toList();
        if (expired.isEmpty()) {
            return;
        }
        expired.forEach(entries::remove);
        rewrite();
    }

    private void load() {
        if (!Files.exists(path)) {
            return;
        }
        try {
            for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                if (line.isBlank()) {
                    continue;
                }
                GuardianAuditEntry entry = GuardianAuditEntryCodec.fromJsonLine(line);
                entries.put(entry.auditId(), entry);
            }
        } catch (IOException exception) {
            throw new UncheckedIOException("Failed to load Guardian audit trail from " + path, exception);
        }
    }

    private void append(GuardianAuditEntry entry) {
        try {
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            Files.writeString(path, GuardianAuditEntryCodec.toJsonLine(entry) + "\n",
                StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException exception) {
            throw new UncheckedIOException("Failed to append to Guardian audit trail at " + path, exception);
        }
    }

    private void rewrite() {
        try {
            StringBuilder content = new StringBuilder();
            for (GuardianAuditEntry entry : entries.values()) {
                content.append(GuardianAuditEntryCodec.toJsonLine(entry)).append('\n');
            }
            Files.writeString(path, content.toString(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException exception) {
            throw new UncheckedIOException("Failed to rewrite Guardian audit trail at " + path, exception);
        }
    }
}
