// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.oblivios.decoy.content.country.Packs;
import org.oblivios.decoy.content.persona.ChildAnswer;
import org.oblivios.decoy.content.persona.ParentAnswer;
import org.oblivios.decoy.content.persona.PersonaProfile;
import org.oblivios.decoy.content.persona.PersonaProfile.Gender;
import org.oblivios.decoy.content.story.VolumeCounts;
import org.oblivios.decoy.content.surface.AppSpec;
import org.oblivios.decoy.content.surface.ChosenPhoto;
import org.oblivios.decoy.content.surface.DeviceCameraProfile;
import org.oblivios.decoy.content.surface.ServerProbe;

/** What the tests give the library beside the profile. */
public final class TestSetup {
    private TestSetup() {}

    /** A persona of {@code p} on 26 November of {@code year}, with the pack of its inputs. */
    public static Persona derive(Seed seed, PersonaProfile p, int year) {
        PersonaInputs in = p.inputs();
        return Persona.derive(Draws.of(seed), p, LocalDate.of(year, 11, 26),
                Packs.forDevice(in.countryCode(), in.zone(), in.locale()).pack());
    }

    /** A device as a test sees it; no value of it was read on a phone. */
    public static DeviceFacts device() {
        return new DeviceFacts("Test Phone", "Test Phone", Map.of(), List.of("Tone"), "Tone", Map.of(), 1080, 2400);
    }

    public static SetupInputs inputs(VolumeCounts counts, List<ChosenPhoto> chosen, List<AppSpec> apps, String currentSsid,
            DeviceCameraProfile cam, ServerProbe probe, Instant now) {
        return new SetupInputs(counts, chosen, apps, currentSsid, cam, probe, now, "", "", device());
    }

    /** Children of these ages, everything else open. */
    public static List<ChildAnswer> aged(Integer... ages) {
        return Arrays.stream(ages).map(a -> new ChildAnswer(a, null, null, null, null)).toList();
    }

    /** A mother and a father, both alive or both dead. */
    public static List<ParentAnswer> parents(boolean alive) {
        return List.of(new ParentAnswer(Gender.FEMALE, alive, null), new ParentAnswer(Gender.MALE, alive, null));
    }
}
