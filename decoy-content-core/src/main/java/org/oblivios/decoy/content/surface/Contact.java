// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.surface;

import java.time.Instant;
import java.time.LocalDate;

public record Contact(String id, String name, String number, boolean favourite, String group, LocalDate birthday,
        Instant lastContacted, PhotoKind photo) {}
