// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.surface;

import java.util.ArrayList;
import java.util.List;

/** Packages of the image that a person uses every day, next to the apps the owner picked. */
final class SystemApps {
    static final List<String> PACKAGES = List.of("com.android.messaging", "com.android.dialer", "app.vanadium.browser",
            "app.grapheneos.camera", "com.android.deskclock", "com.android.calendar", "com.android.contacts",
            "com.android.documentsui");

    private SystemApps() {}

    static List<String> usable(SurfaceContext ctx) {
        List<String> all = new ArrayList<>(PACKAGES);
        ctx.apps().forEach(a -> all.add(a.pkg()));
        return all;
    }
}
