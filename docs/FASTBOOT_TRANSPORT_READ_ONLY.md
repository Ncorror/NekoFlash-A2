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
