// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.story;

/** A place of the story with the coordinates that photos, Wi-Fi networks and trips of that place share. */
public record Place(String name, double lat, double lon, PlaceKind kind) {}
