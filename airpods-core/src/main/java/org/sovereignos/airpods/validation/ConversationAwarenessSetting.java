// SPDX-License-Identifier: Apache-2.0
package org.sovereignos.airpods.validation;

/** The "conversation awareness" setting (control identifier 0x28). */
public record ConversationAwarenessSetting(boolean enabled) implements AirPodsMessage {
}
