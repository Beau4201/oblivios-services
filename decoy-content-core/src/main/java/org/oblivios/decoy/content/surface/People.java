// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.surface;

import java.util.List;

/** Contacts, call log, SMS and voicemail of one life story; {@code ownNumber} is the decoy phone's own number. */
public record People(String ownNumber, List<Contact> contacts, List<Call> calls, List<SmsThread> threads,
        List<Voicemail> voicemails) {}
