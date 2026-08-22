# Fastboot read-only transport bring-up

This document records the first A2 Fastboot transport slice after the fixed ADB
read-only probe became CI-verified and hardware-verified.

## 1. Legacy source

The executable behavior reference remains the supplied legacy release:

- NekoFlash `6.0.0-alpha10.2` / versionCode `233`;
- release commit/tag line: `a1493e2346b702af420f5b658354f76f073848a2`;
- supplied `legacy.zip` SHA-256:
  `54d4f883953be5b2a7550c86c7be30d207495313c12b93651775f7e497ee7ad2`.

The relevant source is legacy `FastbootProtocol.connect()`,
`FastbootProtocol.qualifyConnection()`, `getVar()`, `readGetVarResponse()`, and the
short-command `bulkWrite()` path.

## 2. First A2 Fastboot boundary

The first A2 Fastboot slice is deliberately narrower than the full legacy protocol:

`candidate -> Android USB permission -> openDevice -> claimInterface -> settle 350 ms ->
write exactly one getvar:product -> read Fastboot response -> qualified peer`

Only `getvar:product` is exposed. There is no generic Fastboot command API, no download
phase, and no mutation path.

Protected behavior migrated from legacy:

- the coordinator-selected interface is preferred and must still have vendor-specific
  bulk IN/OUT endpoints;
- canonical Android Fastboot is `0xFF/0x42/0x03`;
- a generic candidate must not look like ADB protocol `0x01`;
- legacy automatic attach/startup/mode-switch paths may classify a single generic bulk
  pair and let the real `getvar:product` exchange qualify it;
- explicit manual Search/chooser selection of a generic Fastboot fallback keeps the
  existing warning/confirmation boundary before permission/access;
- short Fastboot commands use synchronous USB bulk OUT;
- the command is ASCII and is not NUL-terminated;
- qualification waits 350 ms before the first command;
- `getvar:product` has a 7 second overall response budget;
- IN reads use 900 ms slices;
- three empty/failed reads may fail the generation only after at least 1500 ms of
  patience, with 100 ms retry delay, matching the hardware-proven legacy timing fix;
- `INFO`/`TEXT` may carry the value before final `OKAY`;
- `OKAY` qualifies the peer even if product is absent;
- protocol-level `FAIL` for optional `getvar:product` still proves that the peer speaks
  Fastboot and therefore qualifies the connection with product unreported;
- timeout, short command write, lost interface, open/claim failure, or other transport
  ambiguity fails closed;
- a failed generation never automatically closes/reopens/re-sends the command;
- retry after failure requires an explicit manual USB Refresh;
- detach closes the active Fastboot transport;
- ADB and Fastboot transport observations remain separate from descriptor-level
  `CANDIDATE_READY`.

## 3. Explicitly out of scope

This slice does not send:

- `getvar:all`;
- `download:`;
- `flash:`;
- `erase:`;
- `set_active:`;
- `reboot`, `reboot-bootloader`, or `reboot-fastboot`;
- OEM/flashing/unlock commands;
- any arbitrary terminal command.

After exact-head CI succeeds, the first hardware validation is only manual entry into
Fastboot plus observation of the fixed `getvar:product` qualification. Destructive
validation remains last.

## 4. Expected diagnostics

A successful canonical Fastboot connection should contain the core sequence:

`USB_CANDIDATE_READY -> FASTBOOT_CONNECT_STARTED -> FASTBOOT_USB_INTERFACE ->
FASTBOOT_INTERFACE_CLAIMED -> FASTBOOT_HANDSHAKE_STARTED -> FASTBOOT_COMMAND_SENT ->
FASTBOOT_RESPONSE -> FASTBOOT_HANDSHAKE_CONFIRMED -> FASTBOOT_READ_ONLY_PROBE_RESULT ->
FASTBOOT_CONNECT_ENDED`

For the current POCO X3 Pro patient, the expected product is normally `vayu`. A
protocol-level `FAIL` remains a qualified Fastboot peer but must be recorded as
`product=unreported` rather than inventing a product value.

## 5. Stage 6C1 exact-head verification record

The fixed `getvar:product` slice is complete for its defined read-only scope at exact
commit:

`5b6b69f791663204a0abac8f32040fb5e3a1f9f6`

Reviewed exact-head CI evidence:

- reports ZIP SHA-256:
  `a607e2d73fd623a8539cab9c6f8eafc1a3d3be413488f82d40f4e0f3b867861a`;
- 140/140 unit tests passed;
- lint passed with 0 errors / 4 known non-blocking warnings;
- `assembleDebug` passed.

Reviewed hardware evidence on the POCO X3 Pro Fastboot peer:

- first diagnostics ZIP SHA-256:
  `8ef08fe7541ae3133cdfaa853e99ed1cfc9410b969d0b78fa6c8f1121da688a2`;
- cumulative detach/reconnect diagnostics ZIP SHA-256:
  `a16060babc4bb7c9cfd44e7e10414f9fc1a95dd582b0f9b77882bf6915f871da`;
- connected-state screenshot SHA-256:
  `07874fe2268e4d47834487b9f9c66a9e037cdc57b2c4f8438b97a183bf467f22`.

The patient identified as canonical Fastboot (`0xFF/0x42/0x03`, bulk IN `0x81`,
bulk OUT `0x01`). Two separate transport generations both produced:

`getvar:product -> OKAY vayu`

A physical detach produced `FASTBOOT_TRANSPORT_STOP reason=device_detached` followed by
`FASTBOOT_TRANSPORT_CLOSED`. After reconnect, a manual USB Refresh while the same healthy
candidate was active produced `USB_DUPLICATE_CANDIDATE_IGNORED`; it did not create a
second transport or send a duplicate qualification command.

Therefore Stage 6C1 is **CI-verified and hardware-verified only for Fastboot open/claim,
fixed `getvar:product` qualification, detach/reconnect, and duplicate-refresh suppression**.
No mutation path is implied by this PASS.

## 6. Stage 6C2 fixed core read-only diagnostics

The next slice mechanically follows the beginning of legacy
`FastbootProtocol.refreshDiagnostics(force = true, knownProduct = qualifiedProduct)` but
keeps a much smaller command surface. After the already proven product qualification,
A2 may issue only these four point queries, in this exact order:

1. `getvar:current-slot`
2. `getvar:slot-count`
3. `getvar:unlocked`
4. `getvar:max-download-size`

Each point query keeps the legacy default 5 second budget and the already pinned
900 ms read slices / three failed reads / 100 ms retry delay / 1500 ms minimum patience.
A protocol-level `FAIL` means that one optional variable is unreported and leaves the
session usable. A response timeout, short command write, lost interface, or other
transport ambiguity fails the generation closed and requires explicit manual USB Refresh
before another attempt.

`max-download-size` parsing remains legacy-faithful: the first hexadecimal `0x...` or
decimal token is converted to bytes when possible, while the raw value is retained.

This slice still exposes no arbitrary `getvar`, no `getvar:all`, no partition probing,
no `download:`, no flash/erase/set_active/reboot/OEM/unlock command, and no generic
Fastboot terminal.
