# Stage 6B — ADB transport migration and evidence

This document records the durable ADB transport rules and reviewed evidence for the
NekoFlash A2 clean rewrite. Legacy NekoFlash remains the executable behavioral
reference for ADB wire semantics.

## 1. Legacy source used for the migration

Pinned repository baseline:

`Ncorror/NekoFlash@c49242c771dac9d147597c0d07e9ac1c6d320254`

Hardware-tested legacy release supplied locally during the migration:

- release: `6.0.0-alpha10.2` / versionCode `233`;
- commit/tag line: `a1493e2346b702af420f5b658354f76f073848a2`;
- supplied archive: `legacy.zip`;
- SHA-256: `54d4f883953be5b2a7550c86c7be30d207495313c12b93651775f7e497ee7ad2`.

The supplied archive's relevant ADB/USB files were checked against the release commit.
`AdbProtocol`, `AdbKeyStore`, `AdbPacketDispatcher`, checksum handling,
`DeviceViewModel`, and `UsbDeviceInspector` matched byte-for-byte for the reviewed
release source.

## 2. Stage 6B1 transport contract

The first A2 transport slice preserves this sequence:

`candidate -> Android USB permission -> openDevice -> claimInterface -> one CNXN ->
AUTH -> RSA signature/public key as required -> banner -> one packet reader -> CONNECTED`

Protected invariants:

- one physical transport generation sends exactly one `CNXN`;
- no automatic `close -> reopen -> second CNXN` recovery is introduced;
- first `AUTH TOKEN` is answered with the persisted RSA signature;
- if the peer requests another token, the NUL-terminated ADB public key is sent;
- RSA key material is persisted and reused across reconnects;
- after handshake, USB IN is owned by one bounded single-reader dispatcher;
- dispatcher consumers receive complete packets rather than competing for USB IN;
- matching detach closes the active transport generation;
- transport failure never schedules an automatic reopen;
- an explicit user Refresh may create a new transport generation after failure;
- Fastboot remains unopened during this stage;
- no flash/erase/unlock/reboot/sideload operation is part of this evidence.

## 3. Stage 6B1 CI evidence

Implementation commit:

`b2c1af49c251b78d5456911121b4db793cef7c57`

Reviewed CI reports archive:

`NekoFlash-b2c1af49c251b78d5456911121b4db793cef7c57-reports.zip`

SHA-256:

`8c2941d9b3991164a5963095abab856fa076f74686b424fca54908ab3a31b7ff`

Reviewed result:

- 124 unit tests;
- failures/errors/skipped: `0/0/0`;
- `testDebugUnitTest`: PASS;
- lint: `0 errors / 4 known baseline warnings`;
- `lintDebug`: PASS;
- `assembleDebug`: PASS.

Stage 6B1 is therefore **CI-VERIFIED** for the exact implementation commit above.

## 4. Stage 6B1 hardware evidence

Host:

- POCO F7 / Xiaomi `25053PC47G` / device `onyx`;
- Android 16 / SDK 36.

Patient:

- POCO X3 Pro;
- product/model/device reported by ADB banner:
  `vayu_global` / `M2102J20SG` / `vayu`.

Primary cumulative diagnostics archive:

`NekoFlash-A2-diagnostics-20260821-205805Z.zip`

SHA-256:

`343bbaa66ea63849fbc94a057bc0be3663475bbe274d0d204fb29f76aa464da0`

Successful connected-state screenshot:

`7285.png`

SHA-256:

`18bc6da792cb61580a7a6fcf90ee54ae014c0f55035ad3003e682ebfaaed0c59`

The cumulative diagnostics contain four successful ADB transport generations.
Observed counts are four `ADB_CONNECT_STARTED`, four `ADB_CNXN_SENT`, four
`ADB_READER_STARTED`, and four `ADB_CONNECTED` events.

First authorization path:

`AUTH_REQUIRED -> persisted RSA key creation -> signature -> public key -> banner`

Subsequent reconnects used the persisted signature and reached the banner without
sending the public key again.

Three physical detach events each stopped and closed the active transport. A manual
Refresh while an already-current transport was connected produced
`USB_DUPLICATE_CANDIDATE_IGNORED` and did not create another transport or another
`CNXN`.

Stage 6B1 is therefore **HARDWARE-VERIFIED** only for:

- USB open/claim;
- one-CNXN handshake;
- RSA AUTH and persisted-key reconnect;
- banner parsing;
- one packet-reader startup;
- matching detach/reattach;
- duplicate manual Refresh not duplicating the transport.

It does not prove arbitrary ADB services, shell commands, recovery ADB, Fastboot,
or destructive operations.

## 5. Stage 6B2 fixed read-only service probe

The next slice extends only the already-connected ADB transport with the legacy
stream framing required for one fixed read-only probe:

`A_OPEN -> A_OKAY -> zero or more A_WRTE/host A_OKAY -> A_CLSE/host A_CLSE`

The only service exposed by this slice is:

`shell:getprop ro.product.device`

There is deliberately no generic `shell:` or arbitrary service API yet.

Legacy framing preserved by the A2 stream state machine:

- positive local stream IDs;
- NUL-terminated service payload in `A_OPEN`;
- remote stream ID learned from targeted `A_OKAY`/`A_WRTE`/`A_CLSE`;
- each targeted `A_WRTE` is acknowledged with `A_OKAY`;
- targeted remote `A_CLSE` is acknowledged with host `A_CLSE`;
- stale `WRTE`/`CLSE` for another local ID are closed rather than contaminating the
  active stream;
- early `WRTE` during open is ACKed but, matching the reviewed legacy
  `openAdbStream` behavior, is not included in the command result before `A_OKAY`;
- probe output is bounded to 64 KiB;
- service timeout/failure closes only the stream when possible and does not invent
  transport reopen/retry;
- the fixed probe is attempted only for peers classified as `DEVICE` or `RECOVERY`;
  `SIDELOAD` and unknown peers are not given a shell probe.

Hardware status for Stage 6B2 before a post-CI device run:

**NOT YET VERIFIED**.

## 6. Ordering after Stage 6B2

Do not move to Fastboot or destructive behavior until the fixed read-only ADB stream
probe is CI-green and hardware-observed.

After that, any expansion from the fixed probe into broader shell/service support must
continue to be migrated mechanically from the supplied legacy behavior with regression
coverage for stream IDs, stale close handling, dispatcher ownership, and reconnect.
