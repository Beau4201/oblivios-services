// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.surface;

import java.time.Instant;
import java.util.Set;

/**
 * A photo the owner picked at setup. {@code taken} is the real time of the file; the plan moves it to a time of
 * the story and keeps its time of day. {@code stripFields} are the metadata fields the owner marked sensitive.
 */
public record ChosenPhoto(String path, Instant taken, Set<String> stripFields) {
    public ChosenPhoto {
        stripFields = Set.copyOf(stripFields);
    }
}
