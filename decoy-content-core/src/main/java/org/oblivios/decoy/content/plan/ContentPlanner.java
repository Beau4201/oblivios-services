// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.plan;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.oblivios.decoy.content.Persona;
import org.oblivios.decoy.content.PersonaInputs;
import org.oblivios.decoy.content.Seed;
import org.oblivios.decoy.content.country.CountryPack;
import org.oblivios.decoy.content.country.Packs;
import org.oblivios.decoy.content.lang.LanguagePack;
import org.oblivios.decoy.content.persona.PersonaProfile;
import org.oblivios.decoy.content.pool.ConversationPicker;
import org.oblivios.decoy.content.pool.FilledConversation;
import org.oblivios.decoy.content.pool.Judge;
import org.oblivios.decoy.content.pool.Picked;
import org.oblivios.decoy.content.pool.PoolConversation;
import org.oblivios.decoy.content.pool.Rewriter;
import org.oblivios.decoy.content.style.StyleBook;
import org.oblivios.decoy.content.story.Timeline;
import org.oblivios.decoy.content.story.VolumeCounts;
import org.oblivios.decoy.content.story.VolumeModel;
import org.oblivios.decoy.content.style.Styles;
import org.oblivios.decoy.content.surface.AppSpec;
import org.oblivios.decoy.content.surface.AppsSurface;
import org.oblivios.decoy.content.surface.BrowserSurface;
import org.oblivios.decoy.content.surface.CalendarPlan;
import org.oblivios.decoy.content.surface.CalendarSurface;
import org.oblivios.decoy.content.surface.ChosenPhoto;
import org.oblivios.decoy.content.surface.DeviceCameraProfile;
import org.oblivios.decoy.content.surface.DeviceSurface;
import org.oblivios.decoy.content.surface.MailSurface;
import org.oblivios.decoy.content.surface.MediaPlan;
import org.oblivios.decoy.content.surface.MediaSurface;
import org.oblivios.decoy.content.surface.NetworkSurface;
import org.oblivios.decoy.content.surface.People;
import org.oblivios.decoy.content.surface.PeopleSurface;
import org.oblivios.decoy.content.surface.ServerProbe;
import org.oblivios.decoy.content.surface.SurfaceContext;
import org.oblivios.decoy.content.surface.TracesSurface;
import org.oblivios.decoy.content.surface.TravelPlan;
import org.oblivios.decoy.content.surface.TravelSurface;
import org.oblivios.decoy.content.surface.UsageSurface;
import org.oblivios.decoy.content.surface.VaultSurface;

/**
 * Builds the whole plan from the persona inputs, the owner's counted volume and choices, and one seed. Every surface
 * draws from its own named stream of that seed, so the surfaces do not shift each other's values.
 *
 * <p>Throws {@link IllegalArgumentException} for a country without a verified number range or without place data:
 * there is no plan without contacts, and no number is invented. The setup shows that as the refusal of the country
 * step and offers another country. It throws {@code no conversation pool for <tag>} when the pool holds no conversation
 * in the persona's language: the personal threads come only from the pool. Each picked conversation is rewritten by
 * {@code rewriter} and judged by {@code judge} (the local model on the device).
 */
public final class ContentPlanner {
    private ContentPlanner() {}

    public static ContentPlan build(PersonaProfile profile, VolumeCounts counts, List<ChosenPhoto> chosen, List<AppSpec> apps,
            String currentSsid, DeviceCameraProfile cam, ServerProbe probe, Instant now, Seed seed, List<PoolConversation> pool,
            Rewriter rewriter, Judge judge) {
        PersonaInputs in = profile.inputs();
        Persona persona = Persona.derive(seed, profile, now.atZone(in.zone()).getYear());
        if (pool.stream().noneMatch(c -> c.language().equals(persona.language()))) {
            throw new IllegalArgumentException("no conversation pool for " + persona.language());
        }
        CountryPack pack = Packs.forDevice(in.countryCode(), in.zone(), in.locale()).pack();
        VolumeModel volume = VolumeModel.fromCounts(counts, seed);
        Timeline timeline = Timeline.generate(persona, volume, pack, now, seed);
        People generated = PeopleSurface.generate(persona, timeline, pack, seed);
        StyleBook styles = Styles.book(persona, generated, seed);
        Picked picked = ConversationPicker.pick(persona, generated, styles, timeline, pack, pool, seed, rewriter, judge);
        List<FilledConversation> conversations = new ArrayList<>();
        picked.byContact().values().forEach(conversations::addAll);
        conversations.addAll(picked.groups());
        People people = PeopleSurface.withConversations(generated, conversations);

        LanguagePack lang = LanguagePack.of(persona.language());
        CalendarPlan calendar = CalendarSurface.generate(people, timeline, pack, lang, seed);
        TravelPlan travel = TravelSurface.generate(timeline, pack, lang, seed);
        SurfaceContext ctx = new SurfaceContext(persona, timeline, people, pack, apps, currentSsid, now, seed);
        MediaPlan media = MediaSurface.generate(volume, timeline, travel, pack, cam, chosen, seed);

        List<String> warnings = new ArrayList<>(volume.warnings());
        warnings.addAll(timeline.warnings());
        warnings.addAll(media.warnings());
        warnings.addAll(picked.warnings());
        return new ContentPlan(persona, timeline, people, calendar, travel, DeviceSurface.generate(ctx), NetworkSurface.generate(ctx),
                UsageSurface.generate(ctx), AppsSurface.generate(ctx), BrowserSurface.generate(ctx), VaultSurface.generate(ctx),
                TracesSurface.generate(ctx), media, MailSurface.generate(persona, timeline, pack, probe, seed), styles, conversations,
                picked.reserve(), warnings);
    }
}
