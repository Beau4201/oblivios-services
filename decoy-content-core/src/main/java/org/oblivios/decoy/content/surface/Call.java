// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.surface;

import java.time.Instant;

public record Call(String number, Instant at, int durationSeconds, CallType type) {}
