// SPDX-License-Identifier: Apache-2.0
package org.sovereignos.airpods.platform;

import org.sovereignos.airpods.validation.ListeningModeState.ListeningMode;

public record SetListeningMode(ListeningMode mode) implements ControlCommand {
}
