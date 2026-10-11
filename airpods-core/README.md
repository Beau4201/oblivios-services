# airpods-core

Platform-independent logic for AirPods support: the codec for the AirPods
control messages (`AapCodec`), the validated settings and states, and the
state machine that the platform integration drives.

## Provenance

The byte layouts of the control messages were taken from the protocol notes of
the LibrePods project, which are published under GPL-3.0. Only the layouts
(facts about a protocol) were taken. No code and no text was copied, and the
codec was written from tables restated in the project's own words. The notes
were taken with AirPods Pro 2 (USB-C) firmware 7A305; other models and firmware
may differ.
