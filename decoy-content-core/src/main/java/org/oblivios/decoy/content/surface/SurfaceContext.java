// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.surface;

import java.time.Instant;
import java.util.List;
import org.oblivios.decoy.content.Persona;
import org.oblivios.decoy.content.Draws;
import org.oblivios.decoy.content.country.CountryPack;
import org.oblivios.decoy.content.story.Timeline;

/** What every trace generator reads: the story so far, the picked apps, and the network the phone is on now. */
public record SurfaceContext(Persona persona, Timeline timeline, People people, CountryPack pack, List<AppSpec> apps,
        String currentSsid, Instant now, Draws draws) {}
