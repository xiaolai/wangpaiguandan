# Device and human evidence

Plan section 6: each procedure is written here as a checklist before it is run, and each result
records device model, OS version and radio settings. A result is a separate file
`HP-n-result-<date>.md` ending with an `Outcome:` line. None has been run.

**2026-10-16: HP-1 … HP-8 are deferred by the project owner (devices later).** Gates G4 and G5 stay unsigned for the same reason. G1a, G1b (rules) are signed, G2 has an owner-delegated default implementation, and G3 is decided (native Swift core; not ported), so the "Blocked on" column below names what remains.

| ID | Procedure | Status | Blocked on |
|---|---|---|---|
| HP-1 | [Solo match in airplane mode; minimum-device budgets](HP-1-solo-airplane.md) | Not run | A physical phone named by G5 (unsigned); traffic capture; iPhone half needs WI-11 |
| HP-2 | [Four phones + two spectators over LAN](HP-2-lan-four-phones.md) | Not run on phones; software part runs on emulators (`tools/e2e/`, `HP-2-emulator-run-*`); real-phone radio part owed | Six phones on one Wi-Fi with WAN off; G4 signed for a releasable result (G1b signed; G2 owner-delegated default, no external cryptography review) |
| HP-3 | [Bluetooth 4 + 2, 60 minutes, both host platforms](HP-3-bluetooth-4plus2.md) (steps in [ble-device-spike.md](../ble-device-spike.md)) | Not run | G5 matrix phones; iPhone host needs WI-11 (G3 decided native Swift; not ported) |
| HP-4 | [TalkBack and VoiceOver deal, 200% text, grayscale](HP-4-screen-reader.md) | Not run | A regular TalkBack user; VoiceOver needs WI-11 |
| HP-5 | [Human partner sessions with the baseline AI](HP-5-ai-partner.md) | Not run | Human players |
| HP-6 | [Signed release upgrade, restore, mixed-platform deal](HP-6-release-upgrade.md) | Not run | Release key, phones (G1a/G1b signed and MIT license recorded 2026-10-16); step 6 needs WI-11 |
| HP-7 | [Older-player hand layout A vs B](HP-7-hand-layout.md) | Not run | Layout B not built (decision open) |
| HP-8 | [OEM interruption matrix](HP-8-oem-interruptions.md) (Bluetooth steps in [ble-device-spike.md](../ble-device-spike.md)) | Not run | Every G5 phone; iPhone rows need WI-11 |
