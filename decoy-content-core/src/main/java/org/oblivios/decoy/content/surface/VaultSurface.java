// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.surface;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.random.RandomGenerator;

/**
 * Logins for some of the sites the browser history shows. The apps the owner signs in to by hand fill the vault
 * themselves (ADR-317); these are the ones a person accumulates over the years. Passwords are random and work nowhere.
 */
public final class VaultSurface {
    private static final String ALPHABET = "abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    private VaultSurface() {}

    public static VaultPlan generate(SurfaceContext ctx) {
        RandomGenerator r = ctx.seed().stream("vault");
        List<String> top = BrowserSurface.generate(ctx).bookmarks();
        String[] name = ctx.persona().inputs().ownerName().toLowerCase(Locale.ROOT).split("\\s+");
        String handle = name[0] + (name.length > 1 ? "." + name[name.length - 1] : "");
        List<VaultEntry> entries = new ArrayList<>();
        int count = Math.max(1, Math.min(top.size(), 2 + r.nextInt(4)));
        for (int i = 0; i < count; i++) {
            StringBuilder pw = new StringBuilder();
            for (int k = 0; k < 14; k++) {
                pw.append(ALPHABET.charAt(r.nextInt(ALPHABET.length())));
            }
            entries.add(new VaultEntry(top.get(i), handle + (r.nextBoolean() ? "" : String.valueOf(70 + r.nextInt(30))), pw.toString()));
        }
        return new VaultPlan(entries);
    }
}
