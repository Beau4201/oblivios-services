// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.check;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.oblivios.decoy.content.Persona;
import org.oblivios.decoy.content.origin.Origin;
import org.oblivios.decoy.content.persona.PersonaProfile.Gender;
import org.oblivios.decoy.content.plan.ContentPlan;
import org.oblivios.decoy.content.pool.FilledConversation;
import org.oblivios.decoy.content.story.Place;
import org.oblivios.decoy.content.style.StyleBook;
import org.oblivios.decoy.content.surface.Contact;
import org.oblivios.decoy.content.surface.Relation;
import org.oblivios.decoy.content.surface.Trip;
import org.oblivios.decoy.content.world.World;

/**
 * What the origin checker knows of a plan beyond its views: the persona and its world, the places and the spans away,
 * the conversations with the reserve, and the style book. Today's plan has no chronicle, so the closeness of a contact
 * is its order, and places and spans come from the timeline and the travel plan.
 */
public final class PlanStory {
    /** A person of the story; {@code closeness} 0 is the closest. */
    public record Person(String id, Relation relation, Gender gender, int birthYear, int closeness) {}

    /** A span away from home, {@code what} naming its event (a trip id today), with the places of the span. */
    public record Span(Instant from, Instant to, String what, List<Place> places) {
        public Span {
            places = List.copyOf(places);
        }
    }

    private final Persona persona;
    private final World world;
    private final ZoneId zone;
    private final Instant now;
    private final Instant phoneStart;
    private final List<Place> places;
    private final List<Span> away;
    private final Map<String, Person> byNumber;
    private final List<FilledConversation> conversations;
    private final List<FilledConversation> reserve;
    private final StyleBook styles;

    private PlanStory(ContentPlan p) {
        persona = p.persona();
        world = p.world();
        zone = p.timeline().zone();
        now = p.timeline().now();
        phoneStart = p.timeline().phoneStart();
        places = p.timeline().places();
        List<Span> spans = new ArrayList<>();
        for (Trip t : p.travel().trips()) {
            spans.add(new Span(t.from(), t.to(), t.id(), t.places()));
        }
        away = List.copyOf(spans);
        Map<String, Person> people = new HashMap<>();
        List<Contact> contacts = p.people().contacts();
        for (int i = 0; i < contacts.size(); i++) {
            Contact c = contacts.get(i);
            people.putIfAbsent(c.number(), new Person(c.id(), c.relation(), c.gender(), c.birthYear(), i));
        }
        byNumber = Map.copyOf(people);
        conversations = p.conversations();
        reserve = p.reserve();
        styles = p.styles();
    }

    public static PlanStory of(ContentPlan p) {
        return new PlanStory(p);
    }

    public Persona persona() {
        return persona;
    }

    public World world() {
        return world;
    }

    /** The entry or event an origin names; empty when the story holds none. Today only the world holds entries. */
    public Optional<Object> resolve(Origin o) {
        if (o.kind() != Origin.Kind.WORLD) {
            return Optional.empty();
        }
        return world.person(o.id()).map(Object.class::cast).or(() -> world.place(o.id()).map(Object.class::cast));
    }

    public Optional<Person> personByNumber(String e164) {
        return Optional.ofNullable(byNumber.get(e164));
    }

    public List<Span> away() {
        return away;
    }

    /** The places of the persona's life at home: home, work, cafes and shops. */
    public List<Place> places() {
        return places;
    }

    public ZoneId zone() {
        return zone;
    }

    /** The moment the story ends. */
    public Instant now() {
        return now;
    }

    /** The day the persona started using the phone. */
    public Instant phoneStart() {
        return phoneStart;
    }

    /** The pool conversations of the threads. */
    public List<FilledConversation> conversations() {
        return conversations;
    }

    /** The rewritten conversations kept back for the roll-forward, which writes them later without a check. */
    public List<FilledConversation> reserve() {
        return reserve;
    }

    public StyleBook styles() {
        return styles;
    }
}
