// SPDX-License-Identifier: Apache-2.0
package org.oblivios.guardian.overview;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

public record GuardianOverviewSnapshot(
    List<GuardianOverviewEntry.ActiveProtection> activeProtections,
    List<GuardianOverviewEntry.MeaningfulChange> changes,
    List<GuardianOverviewEntry.ProtectionAttention> protectionAttention) {

    public GuardianOverviewSnapshot {
        activeProtections = sortedCopy(activeProtections);
        changes = sortedCopy(changes).stream().limit(3).toList();
        protectionAttention = sortedCopy(protectionAttention);
    }

    private static <T extends GuardianOverviewEntry> List<T> sortedCopy(List<T> entries) {
        Objects.requireNonNull(entries, "entries");
        return entries.stream()
            .peek(entry -> Objects.requireNonNull(entry, "entry"))
            .sorted(Comparator.comparing(GuardianOverviewEntry::occurredAt).reversed())
            .toList();
    }
}
