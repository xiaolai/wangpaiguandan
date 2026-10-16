# Table UI state coverage (WI-08b, WI-09, WI-10)

Source: `design/table-state-inventory.json` (T1–T13 portrait, L1–L10 layouts) and
`design/screen-inventory.json`. WI-08b built solo play; WI-09 added rooms, the lobby, LAN play and
the watcher's screens (T6, T7, T9, T10 and the room screens listed below).

## Built

| ID | State | Where | Checked by |
|---|---|---|---|
| T1 | Follow: beat a pair of 6 | `TableScreen` turn line, seats, trick, preview, pass/play | `TurnClarityTest`, `WildcardPreviewTest` |
| T2 | Lead a new trick | turn line `turn_you_lead`, play enabled from legality | `TurnClarityTest`, `SoloDealEndToEndUiTest` |
| T3 | Opponent bomb | bomb flash overlay; static highlight in reduced motion | `EventEffectTest` |
| T4 | Tribute | tribute button, only legal tribute cards selectable, public transfers in the trick area | `LargeTextClipTest`, `SoloDealEndToEndUiTest` (when deal 2 needs it), `TableControlsTest` (state) |
| T5 | Return tribute | return button, legal return cards only | `TableControlsTest` (state) |
| T6 | Partner disconnected, grace running | seat panel: player name and "暂时离开（N 秒）" from the roster's grace; host banner (H1) | `RoomTablePartsTest` (device), `LobbyStateTest`/`RoomRig` (state) |
| T7 | AI takes over a seat | seat panel "AI 代打中" for a stand-in; host's "现在让 AI 代打" (H1) | `RoomTablePartsTest` |
| T8 | Own link lost, reconnecting | banner from `ConnectionState` plus the reconnect button | `TableControlsTest` |
| T9 | Request to view a hand | consent card above the hand (allow / deny), never on the player's own turn (G3) | `RoomTablePartsTest`, `PrivateViewClearTest` |
| T10 | Watcher list | table menu "观众": every watcher, who sees this hand in words, and revoke | `RoomTablePartsTest`, `PrivateViewClearTest` |
| T12 | Table menu | in-table menu overlay (Android equivalent of the iPhone sheet) | `TouchTargetTest`, `LargeTextClipTest`, `WakeLockTest` |
| T13 | Table rules | rules section of the table menu: profile ids, versions, hashes, provisional mark | `TableControlsTest` (summary) |
| L1 | 320 dp small screen | alternate layout (seats as a list, stacked buttons, more hand rows) | `CardSelectionTest`, `TouchTargetTest` |
| L2 | 360 dp, layout A | standard layout | `TouchTargetTest` (411 dp), manual screenshot |
| L3 | 411 dp | standard layout | `TouchTargetTest` |
| L6 | Android landscape, layout A | two-pane landscape layout | `SessionOwnerLifecycleTest` (rotation) |
| L8 | System text 200 % | alternate layout, card sizes measured from the text | `CardSelectionTest`, `LargeTextClipTest` |
| L9 | Dark + four-colour suits | dark theme follows the system; four-colour suits is a setting | `ContrastTokenTest` (both themes) |
| L10 | Large-card mode 130 % | `largeCards` setting scales card text and size | not covered by an automated test |

Also built from `design/screen-inventory.json`: A1 main menu, A3 dealing, A4 settings (menu and in-table),
D6 resistance notice (trick area), R1–R3 results with the next-deal prompt, H8 paused table.

WI-09 room screens (`ui/RoomScreens.kt`, `ui/LobbyScreen.kt`, `ui/WatchScreen.kt`, `ui/RoomRoute.kt`):

| ID | Screen | Checked by |
|---|---|---|
| A1 | menu entries: open a table, find a table, my saved tables; with the `tributeProfile` switch off, no solo or host entry and a note why | `TributeProfileMenuTest`, `TributeProfileSwitchTest`; layout manual (menu tags `menu:host`, `menu:join`, `menu:saved`) |
| B1 | create a table: name, friendly or competitive, absence policy, grace 10/30/60 s | `RulesSummaryTest` (settings reach every member), form manual |
| B7, B9 | lobby: seats, ready, watchers, start blockers from the same `LobbyGate` the host enforces | `LobbyStateTest`, `AdmissionApprovalUiTest` |
| B8 | admission: name, seat or watch, six-digit code to compare (PROVISIONAL G2); also at the table mid-match | `AdmissionApprovalUiTest`, `ReplacementApprovalUiTest` |
| B10 | seat sheet: AI in/out, swap seats (partners chosen by the host) | `SeatArrangementTest`, `AdmissionApprovalUiTest` |
| C1–C3 | searching, table list (name, host, 4-digit table code), none found | `TwoRoomsTest`, `PlayOrWatchUiTest` |
| C4 | play at an open seat, take over an away seat, or watch | `PlayOrWatchTest`, `PlayOrWatchUiTest`, `ReplacementFlowTest` |
| C5–C9 | waiting with the code, denied, incompatible, seat taken or full, connection failed | `TwoRoomsTest`, `PlayOrWatchTest`, `NicknameTest`, `HostLostTest`, `AdmissionApprovalUiTest` (state); join screen manual |
| D1, D2 | rules before ready from the frozen profiles and room settings; changed settings clear ready | `RulesSummaryTest`, `LobbyStateTest`, `AdmissionApprovalUiTest` |
| G1–G7 | public view, ask, waiting, read-only hand, revoked, player left, watchers full | `SpectatorScreensTest`, `PrivateViewClearTest`, `PlayOrWatchTest` |
| R4 | result as a watcher (neutral team names) | `WatchUi` result panel; manual |
| H1 | host: player away, stand in now | `RoomTablePartsTest` |
| H2 | host lost (joiner): automatic rejoin, attempts shown; follows the room to a restarted host's new address | `HostLostTest`, `HostPortChangeRejoinTest`, `NsdRoomDirectoryTest`; table banner manual |
| H3, H6, H7 | saved tables: restore, damaged (delete only), newer version (kept) | `SuspendChoiceTest` |
| H5 | back to the own seat with the stored ticket (proof of the capability, pinned host key; G2), no approval | `SuspendChoiceTest` |
| H9 | host leaves: suspend (retention and expiry shown) or end (deleted for everyone) | `SuspendChoiceTest`, `ConsequenceConfirmTest`, `RoomTablePartsTest` |
| — | rematch (GDN-017) | `RematchTest` |

WI-10 Bluetooth screens (`ui/BluetoothScreens.kt`; shown only in builds with the `bleTransport` flag on):

| ID | Screen | Checked by |
|---|---|---|
| A1 | menu entry 蓝牙牌桌 (`menu:bluetooth`), absent with the flag off | `PermissionStateTest`, `SoloNeedsNoBluetoothPermissionTest` (both builds) |
| — | Bluetooth hub: trial note (capacity not measured on phones), host or find | manual |
| B1 | create over Bluetooth (same form, Bluetooth title and note) | `BleRoomTest` (state); form manual |
| B3 | permission explanation before the system prompt (no location, no network; solo and Wi-Fi need none) | `PermissionStateTest`, `BleGateTest` |
| B4 | denied: try again, same Wi-Fi, solo; denied for good: settings path, open settings, same Wi-Fi, solo | `PermissionStateTest`, `BleGateTest` |
| B6 | radio off: switch on, same Wi-Fi, solo; continues by itself once on | `PermissionStateTest` (`theGateContinuesByItselfOnceTheRadioIsOn`) |
| — | Android 8–11, no Bluetooth LE, cannot advertise (host only): same Wi-Fi, solo | `PermissionStateTest`, `BleGateTest` |
| C1–C3 | Bluetooth search (scan + advert read), searching and none-found texts | `BleRoomBrowserTest`, `BleRoomTest`; screen manual |
| C9 | Bluetooth connection failed or host lost: same Wi-Fi offered, the medium named on screen | `BleFailureFallbackTest` |
| H3 | a saved Bluetooth table is restored over Bluetooth through the gate (over Wi-Fi when the flag is off) | `BluetoothEntryTest` (medium); flow manual |

## Not built

| ID | State | Reason |
|---|---|---|
| T11 | Reconnect failed, iPhone | iPhone client (WI-11) |
| L4 | 360 dp, layout B fan | Layout B awaits the HP-7 usability decision |
| L5 | iPhone, layout A | iPhone client (WI-11) |
| L7 | iPhone landscape, layout B | iPhone client (WI-11) and the layout B decision |
| A2, A5 | First run without a nickname; identity reset | Not part of WI-09; the default nickname is used |
| B2 | Choose a rule profile | The build ships one provisional profile pair; nothing to choose until G1b |
| B5 | Permission denied, iPhone | iPhone client (WI-11) |
| H4 | Restored table waiting for players | Seats show "等玩家回来"; no separate screen |
