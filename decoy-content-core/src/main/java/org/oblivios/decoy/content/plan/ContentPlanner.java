// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.plan;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.oblivios.decoy.content.chronicle.Chronicle;
import org.oblivios.decoy.content.chronicle.ChronicleBuilder;
import org.oblivios.decoy.content.data.DataSet;
import org.oblivios.decoy.content.Draws;
import org.oblivios.decoy.content.Persona;
import org.oblivios.decoy.content.SetupInputs;
import org.oblivios.decoy.content.review.ReviewEdits;
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
import org.oblivios.decoy.content.story.VolumeModel;
import org.oblivios.decoy.content.style.Styles;
import org.oblivios.decoy.content.surface.AppsSurface;
import org.oblivios.decoy.content.surface.BrowserSurface;
import org.oblivios.decoy.content.surface.CalendarPlan;
import org.oblivios.decoy.content.surface.CalendarSurface;
import org.oblivios.decoy.content.surface.DeviceSurface;
import org.oblivios.decoy.content.surface.MailSurface;
import org.oblivios.decoy.content.surface.MediaPlan;
import org.oblivios.decoy.content.surface.MediaSurface;
import org.oblivios.decoy.content.surface.NetworkSurface;
import org.oblivios.decoy.content.surface.People;
import org.oblivios.decoy.content.surface.PeopleSurface;
import org.oblivios.decoy.content.surface.SurfaceContext;
import org.oblivios.decoy.content.surface.TracesSurface;
import org.oblivios.decoy.content.surface.TravelPlan;
import org.oblivios.decoy.content.surface.UsageSurface;
import org.oblivios.decoy.content.surface.VaultSurface;
import org.oblivios.decoy.content.world.World;
import org.oblivios.decoy.content.world.WorldBuilder;

/**
 * Builds the whole plan from the persona inputs, the owner's counted volume and choices, and one seed. Every surface
 * draws from its own named stream of that seed, so the surfaces do not shift each other's values.
 *
 * <p>Throws {@link IllegalArgumentException} for a country without number data or without place data:
 * there is no plan without contacts, and no number is invented. The setup shows that as the refusal of the country
 * step and offers another country. It throws {@code no conversation pool for <tag>} when the pool holds no conversation
 * in the persona's language: the personal threads come only from the pool. Each picked conversation is rewritten by
 * {@code rewriter} and judged by {@code judge} (the local model on the device).
 */
public final class ContentPlanner {
    private ContentPlanner() {}

    public static ContentPlan build(PersonaProfile profile, SetupInputs setup, ReviewEdits edits, Seed seed,
            List<PoolConversation> pool, Rewriter rewriter, Judge judge) {
        if (!edits.isEmpty()) {
            // shortcut: the review is not built, so edits are refused rather than dropped (plan Task 28)
            throw new IllegalArgumentException("edits: the review edits are not applied yet");
        }
        PersonaInputs in = profile.inputs();
        Instant now = setup.now();
        Draws draws = Draws.of(seed);
        CountryPack pack = Packs.forDevice(in.countryCode(), in.zone(), in.locale()).pack();
        DataSet data = DataSet.of(pack.code());
        Persona persona = Persona.derive(draws, profile, now.atZone(in.zone()).toLocalDate(), pack, data);
        if (pool.stream().noneMatch(c -> c.language().equals(persona.language()))) {
            throw new IllegalArgumentException("no conversation pool for " + persona.language());
        }
        try {
            data.names(persona.background());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("background: " + e.getMessage(), e);
        }
        data.numbers(); // records the version of the number formats the contacts are drawn from
        VolumeModel volume = VolumeModel.fromCounts(setup.counts(), draws);
        World world = WorldBuilder.build(persona, pack, data, draws);
        Chronicle chronicle = ChronicleBuilder.frame(persona, world, data, volume, now, draws);
        Timeline timeline = Timeline.of(chronicle, world, LanguagePack.of(persona.language()));
        People generated = PeopleSurface.generate(persona, world, timeline, pack, draws);
        StyleBook styles = Styles.book(persona, generated, draws);
        Picked picked = ConversationPicker.pick(persona, generated, styles, timeline, pack, pool, draws, rewriter, judge);
        List<FilledConversation> conversations = new ArrayList<>();
        picked.byContact().values().forEach(conversations::addAll);
        conversations.addAll(picked.groups());
        People people = PeopleSurface.withConversations(generated, conversations);

        LanguagePack lang = LanguagePack.of(persona.language());
        CalendarPlan calendar = CalendarSurface.generate(people, timeline, pack, lang, draws);
        TravelPlan travel = new TravelPlan(chronicle.trips());
        SurfaceContext ctx = new SurfaceContext(persona, timeline, people, pack, setup.apps(), setup.currentSsid(), now, draws);
        MediaPlan media = MediaSurface.generate(volume, timeline, travel, pack, setup.cam(), setup.chosen(), draws);

        List<String> warnings = new ArrayList<>(volume.warnings());
        warnings.addAll(timeline.warnings());
        warnings.addAll(media.warnings());
        warnings.addAll(picked.warnings());
        Map<String, String> versions = new TreeMap<>(data.versions());
        versions.put("pool", digest(pool));
        return new ContentPlan(persona, world, chronicle, timeline, people, calendar, travel, DeviceSurface.generate(ctx), NetworkSurface.generate(ctx),
                UsageSurface.generate(ctx), AppsSurface.generate(ctx), BrowserSurface.generate(ctx), VaultSurface.generate(ctx),
                TracesSurface.generate(ctx), media, MailSurface.generate(persona, timeline, pack, setup.probe(), draws), styles,
                conversations, picked.reserve(), warnings, versions, setup.simNumber());
    }

    /** A pool has no version of its own, so what it holds names it. */
    private static String digest(List<PoolConversation> pool) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            pool.forEach(c -> sha.update(c.toString().getBytes(StandardCharsets.UTF_8)));
            return String.format("sha256:%016x", ByteBuffer.wrap(sha.digest()).getLong());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is missing", e);
        }
    }
}
