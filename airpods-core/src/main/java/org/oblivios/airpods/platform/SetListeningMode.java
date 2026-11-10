// SPDX-License-Identifier: Apache-2.0
package org.oblivios.airpods.platform;

import org.oblivios.airpods.validation.ListeningModeState.ListeningMode;

public record SetListeningMode(ListeningMode mode) implements ControlCommand {
}
