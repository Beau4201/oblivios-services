// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.surface;

import java.time.Instant;

public record Voicemail(String fromNumber, Instant at, int seconds) {}
