// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.surface;

import java.time.Instant;
import java.util.Set;

/**
 * One file of the decoy gallery.
 *
 * <p>{@code taken} is the capture time (the writer sets EXIF and the media database from it), {@code added} the
 * time the file reached the phone: one calendar day for all items, the restore day of the story. {@code sourceId}
 * is a selection key, never a URL: for {@code CAMERA_FILL} the downloader resolves it to one public-domain image
 * of a large public source, for {@code CAMERA_CHOSEN} it is the owner's file path, for a boarding pass screenshot
 * {@code boardingpass:<trip id>}. {@code copyOf} names the item a {@code COPY} was made from.
 *
 * <p>{@code width} and {@code height} are the stored pixel size; 0 where the real file or the display decides.
 * {@code profileId} is {@link DeviceCameraProfile#id()} for rendered fill. {@code exif} says whether the file
 * carries a camera EXIF block; {@code lat} and {@code lon} are the GPS tag, null where there is none.
 *
 * <p>{@code stripFields} are the metadata fields the writer must remove from the real file before it enters the
 * decoy: the owner's marks plus {@code "gps"} always, because the real tag is the owner's real place. It is empty
 * for everything that is not a chosen photo or a copy of one, and a copy carries the set of its original.
 */
public record MediaItem(MediaKind kind, String name, Instant taken, Instant added, String sourceId, String copyOf,
        int width, int height, boolean exif, String profileId, Double lat, Double lon, Set<String> stripFields) {
    public MediaItem {
        stripFields = Set.copyOf(stripFields);
    }
}
