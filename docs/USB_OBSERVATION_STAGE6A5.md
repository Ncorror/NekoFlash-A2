# Stage 6A5 - USB observation and explicit rescan parity

Baseline A2 commit: `b1298946b463202d54054ff8689192602f6f0134`.
Legacy behavior baseline: `c49242c771dac9d147597c0d07e9ac1c6d320254`.

Stage 6A5 is intentionally split:

- **6A5A** freezes the pure observation/manual-scan contract and regression tests;
- **6A5B** will wire that contract into `UsbSessionCoordinator`, `MainActivity`, and
  the temporary Home UI only after 6A5A CI is green.

This split prevents a UI/lifecycle change from being mixed with an untested USB
selection rule.

## Why this stage exists

Hardware A/B testing showed that the A2 descriptor layer is stable when Android
delivers the USB attach to A2, but the temporary Home screen still renders a
constant empty `HomeUiState`. A valid coordinator candidate can therefore look
visually disconnected.

The same comparison confirmed a legacy recovery path that A2 has not yet restored:
the user-facing Search action performs one fresh `UsbManager.deviceList`
enumeration. It is not an automatic retry loop. Legacy cancels stale startup and
mode-switch callbacks, inspects the current inventory once, advances one candidate,
or requires user choice when several candidates are present.

## Hardware evidence before 6A5

Host:

- Xiaomi `25053PC47G` / `onyx`
- Android 16 / SDK 36

Target:

- POCO X3 Pro / `vayu`
- normal Android ADB device mode
- target charging from the NekoFlash host, matching the pinned legacy expected
  host/power direction

A2 commit `b1298946...` received five consecutive attach cycles when Android's USB
chooser was directed to A2. Every cycle produced the same candidate:

- VID:PID `18D1:4EE7`
- mode `ADB`
- match `CANONICAL`
- interface `0`
- class/subclass/protocol `FF/42/01`
- bulk OUT `0x01`
- bulk IN `0x81`
- max packet `512`

Observed `USB_ATTACHED -> USB_CANDIDATE_READY` latency was approximately
`4.1-10.8 ms`, with no `USB_CANDIDATE_DISAPPEARED` or
`USB_ATTACHED_NO_CANDIDATE` in the five-cycle control run.

The legacy application produced the same descriptor profile and then continued
into `openDevice`, interface claim, ADB `CNXN/AUTH`, RSA authorization, banner, and
single-reader dispatcher. A2 deliberately stops before that transport boundary.

## Observation contract

`UsbSessionCoordinator` remains the sole owner of USB discovery and permission
state. UI may receive only immutable observation snapshots. No `UsbDevice`, raw
endpoint object, permission registry, or Android USB object crosses into Compose.

The UI-safe candidate summary is intentionally small: stable key, display label,
observed mode, and interface index. VID/PID, endpoint objects, and protocol state
remain coordinator/diagnostics concerns.

A future `CANDIDATE_READY` observation means:

- descriptor classification succeeded;
- Android USB permission is available for that candidate;
- diagnostic snapshot capture completed;
- transport is still **not opened**.

Home must therefore say that the device is detected, not that ADB/Fastboot is
connected. Actual connection wording is reserved for the later transport stage.

Activity recreation may replace the listener, but listener replacement must not
change USB ownership or coordinator state. The replacement UI must receive the
current immutable observation rather than reconstructing USB state itself.

## Explicit manual scan contract

One future press on `Refresh USB` performs exactly one current inventory read.

Before that read A2 will cancel only the pending startup scan and active mode-switch
watch, matching the legacy explicit Search boundary. It must not create a timer,
repeat the scan, or introduce automatic recovery.

The pure decision rules frozen in 6A5A are:

- zero compatible candidates: report the current physical inventory only;
- one compatible non-generic candidate: advance that candidate through the existing
  permission policy with `automatic=false`;
- one generic Fastboot fallback candidate: require the same explicit warning/
  confirmation that legacy `connectManualCandidate` required before permission;
- several compatible candidates: do not auto-select, even if one is already the
  current candidate; require explicit user choice as legacy Search did, and a
  subsequently selected generic Fastboot fallback still requires its confirmation;
- one sole non-generic candidate advances through the existing permission/access
  path even when its stable key matches the current candidate; duplicate handling
  remains the coordinator's existing responsibility, matching legacy Search;
- one temporarily empty manual enumeration does not invalidate an existing current
  generation; detach remains the owner of invalidation.

6A5B will additionally revalidate a chooser selection against a **fresh**
device-list snapshot before permission handling, so a stale UI choice cannot inject
an old descriptor into the coordinator.

## Out of scope

Neither 6A5A nor 6A5B adds:

- periodic USB polling;
- background retry/recovery;
- new attach receivers;
- `UsbDeviceConnection` opening;
- interface claim;
- ADB `CNXN/AUTH`;
- Fastboot handshake;
- flashing, sideload, unlock, or destructive operations.

The proven legacy transport rule remains pending for Stage 6B: one transport, one
ADB `CNXN`; no automatic close/reopen and no repeated `CNXN` recovery loop.

## Verification gates

6A5A requires:

- pure manual-scan policy tests pass;
- pure Kotlin compiles with warnings treated as errors;
- normal project unit tests, lint, and `assembleDebug` remain green.

6A5B will then require:

- exact EN/RU resource-key parity;
- Kotlin compiler warnings remain zero;
- lint remains zero errors with only the existing baseline warnings;
- `assembleDebug` succeeds;
- post-CI hardware retest confirms Home reflects the coordinator candidate and one
  explicit Refresh USB can discover an already-present single target.

Hardware status for the future 6A5B UI/rescan wiring remains `NOT YET VERIFIED`.
The pre-change A2 descriptor attach path has the five-cycle evidence recorded above.
