// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.surface;

import java.time.Instant;
import org.oblivios.decoy.content.story.Place;

/** A saved network; {@code place} is the timeline place it belongs to, or null for the network the phone is on now. */
public record WifiNetwork(String ssid, Place place, Instant savedAt) {}
