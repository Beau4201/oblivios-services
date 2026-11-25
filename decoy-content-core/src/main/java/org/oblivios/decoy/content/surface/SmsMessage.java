// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.surface;

import java.time.Instant;

public record SmsMessage(Instant at, boolean incoming, String body, boolean read) {}
