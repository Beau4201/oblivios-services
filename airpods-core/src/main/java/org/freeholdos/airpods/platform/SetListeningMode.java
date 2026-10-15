// SPDX-License-Identifier: Apache-2.0
package org.freeholdos.airpods.platform;

import org.freeholdos.airpods.validation.ListeningModeState.ListeningMode;

public record SetListeningMode(ListeningMode mode) implements ControlCommand {
}
