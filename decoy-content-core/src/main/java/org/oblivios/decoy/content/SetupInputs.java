// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content;

import java.time.Instant;
import java.util.List;
import org.oblivios.decoy.content.story.VolumeCounts;
import org.oblivios.decoy.content.surface.AppSpec;
import org.oblivios.decoy.content.surface.ChosenPhoto;
import org.oblivios.decoy.content.surface.DeviceCameraProfile;
import org.oblivios.decoy.content.surface.ServerProbe;

/**
 * What the setup gives beside the profile: the owner's counted volume and choices, what it reads on the device, and the
 * moment of seeding. {@code simNumber} is the number of the SIM and {@code mailAddress} the owner's throwaway address
 * (ADR-315 (4)).
 */
public record SetupInputs(VolumeCounts counts, List<ChosenPhoto> chosen, List<AppSpec> apps, String currentSsid,
        DeviceCameraProfile cam, ServerProbe probe, Instant now, String simNumber, String mailAddress, DeviceFacts device) {
    public SetupInputs {
        chosen = List.copyOf(chosen);
        apps = List.copyOf(apps);
    }
}
