// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.country;

import java.time.LocalDate;
import java.util.List;
import java.util.random.RandomGenerator;

/**
 * The pack for a device whose country has no pack or no verified number range: global senders only, no local bank
 * or shop, and no numbers of its own (a made-up number could belong to a real person).
 */
public final class NeutralPack implements CountryPack {
    static final List<Sender> GLOBAL = List.of(
            new Sender("Google", SenderKind.GLOBAL), new Sender("Apple", SenderKind.GLOBAL),
            new Sender("Microsoft", SenderKind.GLOBAL), new Sender("WhatsApp", SenderKind.GLOBAL),
            new Sender("Amazon", SenderKind.GLOBAL), new Sender("PayPal", SenderKind.GLOBAL));

    private static final List<String> GIVEN = List.of(
            "Alex", "Sam", "Chris", "Jamie", "Taylor", "Jordan", "Robin", "Morgan", "Casey", "Quinn", "Maria", "Daniel",
            "Sofia", "David", "Laura", "Marco", "Anna", "Thomas", "Julia", "Lucas", "Nina", "Adam", "Elena", "Omar",
            "Mia", "Leo", "Eva", "Noah", "Zoe", "Hugo", "Lena", "Felix", "Clara", "Ivan", "Sara", "Karim", "Emma", "Jonas",
            "Nora", "Tim", "Ida", "Paul", "Rita", "Ben", "Lea", "Max", "Vera", "Dan");
    private static final List<String> FAMILY = List.of(
            "Smith", "Garcia", "Martin", "Novak", "Rossi", "Silva", "Kowalski", "Costa", "Weber", "Lopez", "Jensen",
            "Meyer", "Ricci", "Popov", "Khan", "Santos", "Moreau", "Fischer", "Horvat", "Ivanov", "Lund", "Marino",
            "Nagy", "Ortiz", "Petrov", "Quinn", "Reyes", "Stein", "Todd", "Urban", "Varga", "Wolf", "Young", "Zimmer",
            "Berg", "Cruz", "Dumont", "Egger", "Faber", "Gomez", "Hahn", "Ito", "Jovic", "Keller", "Lang", "Mora");

    @Override
    public String code() {
        return "XX";
    }

    @Override
    public String givenName(RandomGenerator r) {
        return GIVEN.get(r.nextInt(GIVEN.size()));
    }

    @Override
    public String familyName(RandomGenerator r) {
        return FAMILY.get(r.nextInt(FAMILY.size()));
    }

    @Override
    public PhoneNumber mobileNumber(RandomGenerator r) throws NoVerifiedRange {
        throw new NoVerifiedRange("the neutral pack has no verified number range");
    }

    @Override
    public List<Sender> senders(SenderKind kind) {
        return kind == SenderKind.GLOBAL ? GLOBAL : List.of();
    }

    @Override
    public List<LocalDate> publicHolidays(int year) {
        return List.of(LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 25));
    }

    @Override
    public List<String> homeWifiPatterns() {
        return List.of("HOME-####", "WiFi-######", "NETWORK-###");
    }
}
