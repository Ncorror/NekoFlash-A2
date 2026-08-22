# TEMPORARY DEVELOPMENT CHECKPOINT

> **Temporary development-only file.**
>
> This file exists so the active NekoFlash A2 migration state, hardware evidence,
> protected invariants, and next-step ordering are not lost between development
> sessions. It is not a release document and must not become the only source of
> truth for permanent behavior.
>
> **Removal rule:** delete this file before a stable/public release, but only after
> every durable invariant/evidence item that still matters has been moved into the
> permanent behavior contracts and validation documentation.

Last updated: **2026-08-22**

## 0. Recovery card

Use this section first when resuming development after an interrupted chat/session.

Canonical repository:

`https://github.com/Ncorror/NekoFlash-A2`

Latest reviewed A2 implementation/evidence commit:

`118d31b1ad57689985880ccfd3f7829b9ae70679`
`add manual Fastboot getvar all snapshot`

This exact commit contains Stage 6C4 and is backed by reviewed exact-head CI reports
plus two real-device manual `getvar:all` campaigns. The durable Fastboot record is
`docs/FASTBOOT_TRANSPORT_READ_ONLY.md`.

Do not hard-code the commit that contains the current revision of this checkpoint.
Resolve it from Git when resuming:

`git log -1 --format=%H -- docs/DEVELOPMENT_CHECKPOINT_TEMPORARY.md`

Also verify the repository HEAD with `git rev-parse HEAD` and inspect every commit
after the latest reviewed implementation/evidence commit before continuing development.

Current completed implementation stage:

**6C4 — manual privacy-safe `getvar:all` snapshot — CI-VERIFIED / HARDWARE-VERIFIED**

Active implementation stage in the current development patch:

**6C5 — privacy-safe partition inventory/topology from the manual snapshot — NOT YET VERIFIED**

Current transport boundary:

- USB descriptor/discovery/lifecycle and UI observation/manual Refresh exist;
- `CANDIDATE_READY` is still not a protocol connection;
- ADB handshake/AUTH and the fixed `shell:getprop ro.product.device` stream probe are
  hardware-verified;
- Fastboot open/claim, fixed point diagnostics, extended diagnostics, and explicit
  manual `getvar:all` are hardware-verified through exact commit `118d31b1...`;
- Stage 6C5 adds **no new wire command**: it only derives an in-memory partition and
  slot-topology model from the already manual-only `getvar:all` snapshot plus fixed
  non-sensitive point facts;
- raw `getvar:all` payloads and serial values remain excluded from exported diagnostics;
- there is still no automatic `getvar:all`, partition metadata backfill point probing,
  DATA phase, mutation path, generic Fastboot command entry, or generic ADB shell;
- destructive A2 operations: **NOT IMPLEMENTED**.

Latest reviewed CI evidence:

- exact commit: `118d31b1ad57689985880ccfd3f7829b9ae70679`;
- reviewed reports: `NekoFlash-118d31b1ad57689985880ccfd3f7829b9ae70679-reports.zip`;
- reports SHA-256:
  `fe03b2e787d26e88970a10ba8a0b55afa865097cdfb4301bd098135f019dfb77`;
- 164/164 unit tests passed;
- failures/errors/skipped: 0/0/0;
- lint: 0 errors / 4 known baseline warnings;
- `testDebugUnitTest`, `lintDebug`, and `assembleDebug`: success.

Latest reviewed Stage 6C4 hardware evidence:

- first manual-snapshot diagnostics: `NekoFlash-A2-diagnostics-20260822-132535Z.zip`;
- first diagnostics SHA-256:
  `448296d985e2df334405f16503809073dc259f98e158b89350dc950944bc27f9`;
- cumulative detach/reconnect/manual-repeat diagnostics:
  `NekoFlash-A2-diagnostics-20260822-132640Z.zip`;
- cumulative diagnostics SHA-256:
  `6f6ca06c4d84174d201831ba69dfb64d191c8aced0aa8824b8b1aaf2ff06906b`;
- connected-state screenshot: `7358.png`;
- screenshot SHA-256:
  `37d312640a2ea9710dfb13e702dfd4d159e5230902929570bfa978af25012266`;
- the first generation sent no `getvar:all` during automatic connection; the explicit
  button press then sent exactly one manual request;
- every completed snapshot reported `supported=true`, `complete=true`, final `OKAY`,
  `variables=222`, `partitionMetadata=100`, `ignored=2`, `duplicates=2`,
  `conflictingDuplicates=0`, `serialReported=true`, `payloadsRedacted=true`;
- cumulative evidence contains four explicit manual snapshots total: one before detach
  and three later user-triggered snapshots on the healthy second generation; no
  automatic or hidden `getvar:all` occurred;
- all `getvar:all` INFO/TEXT response payloads were exported only as `<redacted>`; raw
  serial values did not appear in diagnostic events;
- detach stopped/closed the first transport, reconnect created a fresh generation, and
  two manual USB Refresh actions on the healthy second generation produced
  `USB_DUPLICATE_CANDIDATE_IGNORED`.

Hardware PASS now covers the Stage 6C4 manual privacy-safe broad snapshot boundary.
Stage 6C5 inventory derivation, later bounded metadata backfill point queries, operation
re-enumeration/cancellation, DATA/download, and every mutation path remain outside this
evidence.

Recovery order:

1. read `docs/ARCHITECTURE_A2_MASTER_PROMPT.md`;
2. read permanent behavior contracts relevant to the active stage;
3. read this checkpoint;
4. verify repository HEAD, checkpoint commit identity, and exact evidence hashes;
5. inspect any commits newer than the latest reviewed implementation/evidence commit;
6. resolve any conflict before changing code;
7. continue only from the active implementation stage recorded above.

Do not use chat memory as the only source of project state.
Do not create recursive checkpoint-only commits merely to record CI for an earlier
checkpoint-only commit; Git history already records those documentation revisions.

## 1. Source of truth and protected behavior

NekoFlash A2 is a clean implementation that preserves proven NekoFlash behavior.
The legacy repository remains the executable behavioral specification.

Pinned legacy baseline:

`Ncorror/NekoFlash@c49242c771dac9d147597c0d07e9ac1c6d320254`

Hardware-tested legacy release observed during the current A/B campaign:

`Ncorror/NekoFlash@a1493e2346b702af420f5b658354f76f073848a2`
(`6.0.0-alpha10.2`)

The USB/application implementation used by that release matches the pinned
baseline for the relevant USB transport files.

Protected unless separately proven and approved:

- USB attach/detach/re-enumeration lifecycle;
- USB permission-result semantics;
- ADB wire behavior;
- Fastboot wire behavior;
- Sideload classification;
- Mi Unlock behavior;
- destructive behavior;
- retry/recovery behavior.

Do not invent periodic USB retries, speculative recovery, vendor guesses, or
automatic transport reopen behavior.

Permanent USB behavior belongs in `docs/USB_BEHAVIOR_CONTRACT.md`.
This temporary file must not override that contract.

## 2. A2 identity and ownership

Final Android identity:

- package: `io.github.ncorror.nekoflash`
- namespace: `io.github.ncorror.nekoflash`
- applicationId: `io.github.ncorror.nekoflash`

Legacy package `ru.forum.adbfastboottool` must not return to A2 source,
namespace, or applicationId.

Ownership boundaries:

- `UsbSessionCoordinator` is the sole owner of USB discovery, permission,
  descriptor state, attach/detach, and re-enumeration;
- later, `OperationCoordinator` is the sole owner of active operation state,
  cancellation, progress, results, and operation diagnostics;
- `FlashOperationService` owns foreground-service lifecycle/notification only;
- UI must not open `UsbDeviceConnection`, claim interfaces, parse USB protocol
  packets, or own JNI/native transport state.

## 3. Approved entry-session boundary

Persisted risk acknowledgement is not USB session authorization.

A cold/full process entry starts unauthorized even if the current risk schema
was acknowledged previously.

Before the user explicitly continues through the entry gate, A2 must not start:

- startup USB enumeration;
- attach processing;
- `UsbManager.requestPermission`;
- auto-connect;
- reconnect automation;
- mode-switch automation.

An attach delivered before authorization is normalized and not replayed as a
stale attach payload. After authorization, A2 performs the normal conservative
`350 ms` enumeration against the descriptors that are present then.

Configuration recreation may preserve authorization.
Authorized root Back/backgrounding preserves the session, matching legacy
`moveTaskToBack(true)`.
Ordinary non-configuration UI-entry destruction revokes the current entry lease
while there is no operation-owned lease.

Broad shared-storage access is not an entry requirement.
Do not add `MANAGE_EXTERNAL_STORAGE` unless a later file workflow proves that
narrow user-selected access cannot preserve required semantics.

## 4. Welcome UI decision

The final Welcome screen will keep the NekoFlash visual identity and the proven
Welcome artwork.

The current Compose entry screen is a functional safety gate, not the final
visual design.

Final Welcome direction:

- keep the NekoFlash artwork/identity;
- keep explicit risk acknowledgement;
- require explicit Continue for each new process entry;
- RU + EN;
- do not restore the old broad-storage gate just for visual parity.

## 5. CI checkpoint

Previous fully reviewed CI baseline before Stage 6A5A:

`b1298946b463202d54054ff8689192602f6f0134`
`finish diagnostics export verification`

Verified result:

- 101/101 unit tests passed;
- failures/errors/skipped: 0;
- Kotlin compiler warnings: 0;
- lint: 0 errors / 4 known baseline warnings;
- `testDebugUnitTest`: success;
- `lintDebug`: success;
- `assembleDebug`: success.

Stage 6A5A implementation commit:

`55d1ff7629cf446ef5294fd689d5061b72e7f390`
`pin USB observation and manual scan contract`

Stage 6A5A reviewed evidence commit:

`8518204b88c1252ab34c2bc2d581f8678af54205`
`add temporary development checkpoint`

That reviewed Stage 6A5A checkpoint had 112/112 tests passing, no
failures/errors/skips, zero observed Kotlin compiler warnings, lint 0 errors /
4 baseline warnings, and successful `testDebugUnitTest`, `lintDebug`, and
`assembleDebug`.

Stage 6A5B implementation/evidence commit:

`828d6dab289d5c7715e15d604a50fa17af73bcff`
`wire Stage 6A5B USB observation and manual refresh`

Reviewed CI result for that exact executable commit:

- GitHub Actions: Android CI #33 — success;
- run ID: `32290292423`;
- reports artifact:
  `NekoFlash-828d6dab289d5c7715e15d604a50fa17af73bcff-reports`;
- original GitHub reports artifact digest:
  `9c849b807d6cfc14086f7ae5bc450458f9049bbe7b4e61a39e41d61caaeca440`;
- debug artifact:
  `NekoFlash-828d6dab289d5c7715e15d604a50fa17af73bcff-debug`;
- original GitHub debug artifact digest:
  `3f35eefd19d70055f8dbc0bf798d851a0e7afc7dcc10bae62855794168b941cb`;
- locally repacked reviewed reports ZIP:
  `NekoFlash-828d6da-reports.zip`;
- local reports SHA-256:
  `b255e820eb6d4c8e9222744423859825a2ece04ade60254a946e4376b41fe6b2`;
- 120/120 unit tests passed;
- all 112 prior test identities remained present and 8 Stage 6A5B regression tests
  were added;
- failures/errors/skipped: 0/0/0;
- Kotlin compiler warnings observed in captured logs: 0;
- lint: 0 errors / 4 known baseline warnings;
- `testDebugUnitTest`: success;
- `lintDebug`: success;
- `assembleDebug`: success.

Stage **6A5B — Android USB observation wiring** is therefore **CI-VERIFIED**
against exact commit `828d6dab289d5c7715e15d604a50fa17af73bcff`.

The commit containing the current revision of this checkpoint is intentionally not
hard-coded here; obtain it from Git history when resuming development. A later
checkpoint-only CI run may verify the documentation commit itself, but do not create
another checkpoint-only commit merely to record that run.

The next implementation stage is **6B — ADB transport, read-only bring-up first**.

## 6. Reference A2 APK used for the first hardware campaign

Reference commit:

`b1298946b463202d54054ff8689192602f6f0134`

Reference debug APK SHA-256:

`6d872bc50109eef23d4b61832724737f4c53ede1c4afe1c8285525b5c4310890`

Keep this binary identity attached to all evidence collected with that APK.

## 7. Diagnostics checkpoint

Diagnostics are isolated per process run under an app-specific run directory.

Export schema:

`io.github.ncorror.nekoflash.usb-diagnostics-export.v1`

The Android export path uses user-selected document creation rather than broad
storage permission.

Real-device host-only preflight already proved:

- entry gate launches;
- coordinator starts only after authorization;
- `350 ms` startup enumeration runs;
- zero attached devices produce `devices=0`;
- no false USB permission request occurs;
- no false USB candidate occurs;
- system document creation works;
- exported ZIP opens and contains a valid manifest and event log.

## 8. Hardware setup currently used as evidence

Host:

- POCO F7
- Xiaomi model `25053PC47G`
- device `onyx`
- Android 16 / SDK 36

Patient:

- POCO X3 Pro
- `vayu`

Observed canonical ADB USB profile:

- VID:PID `18D1:4EE7`
- interface index `0`
- class/subclass/protocol `FF/42/01`
- bulk OUT `0x01`
- bulk IN `0x81`
- max packet size `512`

Hardware-proven connection signal from the legacy test setup:

**the patient charges from the NekoFlash host**.

Treat this as a positive proven signal of the known-good host/patient setup.
Do not dismiss it as irrelevant power-only behavior during this migration.

## 9. A2 hardware discovery evidence

### 9.1 Pre-6A5 control campaign

Control diagnostics artifact:

`NekoFlash-A2-diagnostics-20260818-203856Z.zip`

SHA-256:

`4a01ca60e61ba1cd6afab70d7492b898d9d602b13e9a49dd9b121ade27630c96`

This run contains five USB session snapshots.

Observed result:

- 5/5 `USB_ATTACHED`;
- 5/5 `USB_CANDIDATE_READY`;
- all five selected the same POCO X3 Pro canonical ADB profile;
- no `USB_CANDIDATE_DISAPPEARED`;
- no `USB_ATTACHED_NO_CANDIDATE`;
- candidate remains `transport=not-opened`.

Measured `USB_ATTACHED -> USB_CANDIDATE_READY` latency was approximately:

- 9.2 ms
- 5.4 ms
- 4.8 ms
- 5.2 ms
- 10.8 ms

Average: approximately **7.1 ms**.

Pre-6A5 hardware conclusion:

- Android descriptor mapping: PASS;
- canonical ADB descriptor classification: PASS;
- A2 attach-to-candidate path: PASS in the 5/5 control run;
- detach classification: observed working;
- mode-switch watch lifecycle: observed working;
- actual USB transport: not implemented.

### 9.2 Stage 6A5B post-CI hardware campaign

Executable commit:

`828d6dab289d5c7715e15d604a50fa17af73bcff`

Exact-run debug APK SHA-256:

`ca29e88473da9c9d8b3636435588b06a7fb75caafd52045dfad54a492e308f14`

Reviewed diagnostics:

`NekoFlash-A2-diagnostics-20260819-193354Z.zip`

SHA-256:

`b8f9da9666b616d5428285c5533dfe922d7152c6602e974d55539c33b9d2ab55`

Reviewed UI screenshots:

- `7236.png` — landscape candidate-visible state —
  `6ba362d03dac1e8f77f8679b9eff74a746174f8f93093bb71afa6357b8f80741`;
- `7237.png` — portrait candidate-visible state —
  `54653bd28fee336433c769662e8b342c98771dc1406aaaa0369af82d8f746e83`;
- `7238.png` — detached/no-device state —
  `8b1e70b9bc4a0238d4fd83baee70dc4e666870405429ab27a8c8370d9f2498bc`.

Hardware-observed result:

- descriptor parity remained canonical ADB `18D1:4EE7`, interface 0,
  `FF/42/01`, bulk OUT `0x01`, bulk IN `0x81`, packet 512;
- Home visibly rendered `POCO X3 Pro`, `ADB`, and USB detected;
- the same candidate remained visible across orientation/Activity recreation;
- explicit manual Refresh observed an already-present single target and preserved
  the current generation;
- matching detach cleared the stale Home candidate;
- subsequent reattach returned to `CANDIDATE_READY`;
- diagnostics ordered snapshot capture before `USB_CANDIDATE_READY`;
- reviewed ready events remained `transport=not-opened`.

Stage 6A5B hardware verdict:

- Home observation wiring: **PASS**;
- manual Refresh on an already-present target: **PASS**;
- Activity recreation observation continuity: **PASS**;
- matching detach UI clearing: **PASS**;
- descriptor parity with the pinned legacy behavior: **PASS**;
- transport boundary remains closed: **PASS** for the Stage 6A5B requirement.

Do not generalize this into “ADB is connected” or “A2 flashing is hardware verified”.
ADB transport, Fastboot transport, sideload, unlock, flashing, and destructive behavior
remain outside this evidence boundary.

## 10. Important A/B test caveat: legacy and A2 can compete for attach

Legacy and A2 have different application IDs, so both can be installed together.
Both also advertise a broad vendor-class `USB_DEVICE_ATTACHED` route.

During A/B testing Android displayed a chooser containing both NekoFlash apps.
That means a physical attach may be delivered to either selected handler.

Interpret logs accordingly:

- A2 can receive `USB_DETACHED current=false` for a device whose preceding attach
  was handled by legacy;
- missing A2 attach while legacy was selected is not evidence that A2 descriptor
  discovery failed;
- clean A2 attach tests must explicitly select A2 in the Android chooser or
  otherwise isolate the handler.

This interference explained a significant part of the earlier apparently
unstable attach evidence.

## 11. What legacy proves beyond A2's current stage

Legacy does not stop at descriptor detection.

Proven legacy ADB sequence:

`candidate -> permission -> openDevice -> claimInterface -> CNXN -> AUTH -> RSA ->
ADB banner -> packet dispatcher -> CONNECTED`

The A/B campaign observed the real RSA authorization prompt on the patient.
After authorization, subsequent legacy ADB connections were effectively immediate
at the timestamp resolution used by the legacy logs.

Legacy diagnostic evidence recorded no errors in that session and an
`adb.connect` milestone of about `15 ms` for the last connection.

A2 Stage 6B1 now reproduces that transport sequence on real hardware at exact commit
`b2c1af49c251b78d5456911121b4db793cef7c57`. Four reviewed transport generations
each produced one `CNXN`, one reader startup, and one connected banner.

`CANDIDATE_READY` still remains descriptor + permission state only and must never be
presented as a real ADB/Fastboot protocol connection; ADB transport state is reported
separately.

## 12. Critical ADB transport invariant

Stage 6B preserves the hardware-proven legacy rule:

**one transport, one CNXN**

Do not introduce automatic:

`close -> reopen -> second CNXN`

Legacy records that close/reopen plus another CNXN caused detach/attach cycling
on some Android USB hosts.

The first A2 ADB transport migration was kept mechanical:

`openDevice -> claimInterface -> single CNXN -> AUTH -> banner -> single-reader dispatcher`

This sequence is now hardware-verified at `b2c1af49...`. No speculative
retry/recovery may be added during later stream/service migration.

## 13. Stage 6A5B parity gaps now closed

### 13.1 Home observation wiring

The previous gap where `MainActivity` supplied a constant empty `HomeUiState()` is
closed by Stage 6A5B. Home now renders only coordinator-owned state that has a real
source at this stage:

- detected device label;
- observed USB mode;
- USB observation state.

Final protocol/operation-derived fields such as slot, topology, unlock state, and
active operation remain intentionally absent until their real sources exist.

Hardware screenshots for exact implementation commit `828d6dab...` show
`POCO X3 Pro`, `ADB`, and USB detected, and show the stale candidate cleared after
matching detach.

### 13.2 Legacy manual Search parity

Explicit manual Search/Refresh is restored in the Android runtime.

The pinned legacy executable reference remains:

- `MainActivity.scanForDevices`;
- `MainActivity.showUsbDeviceChooser`;
- `MainActivity.connectManualCandidate`;
- `MainActivity.requestUsbAccess`.

Stage 6A5B preserves the required semantics:

- one explicit action inspects the current USB inventory;
- zero compatible candidates report the physical inventory without inventing retry;
- one compatible candidate advances through the existing access path;
- multiple candidates require explicit user choice;
- generic Fastboot requires extra explicit confirmation;
- one temporarily empty manual enumeration does not tear down an already-current
  generation;
- chooser selection is revalidated against fresh USB state before access.

The two intentional A2 differences and their justification are recorded permanently
in `docs/USB_OBSERVATION_STAGE6A5.md`: one inventory read per explicit Refresh, and
UI-safe candidate summaries instead of exposing Android USB descriptor objects.

## 14. Stage 6A5 observation contract pinned in source

The new pure observation states are:

- `INACTIVE`
- `SCANNING`
- `NO_DEVICE`
- `UNSUPPORTED_DEVICE`
- `MULTIPLE_CANDIDATES`
- `PERMISSION_PENDING`
- `PERMISSION_DENIED`
- `PERMISSION_ERROR`
- `CANDIDATE_READY`

`CANDIDATE_READY` means:

- descriptor selected;
- Android permission ready;
- enough information exists for the future transport layer;

and explicitly does **not** mean:

- `UsbDeviceConnection` opened;
- interface claimed;
- ADB handshake complete;
- Fastboot handshake complete.

UI-safe candidate observation contains only:

- stable key;
- display label;
- observed mode (`ADB` / `FASTBOOT`);
- interface index.

Android `UsbDevice` objects and transport state must not leak into Compose UI.

The pure manual-scan decisions currently pinned include:

- `NoCandidate`
- `PreserveCurrent`
- `Select`
- `ConfirmGenericFastboot`
- `Choose`

## 15. Stage 6A5B completion record

Stage **6A5B — Android USB observation wiring** is complete for its defined scope.

Implementation commit:

`828d6dab289d5c7715e15d604a50fa17af73bcff`

Verified behavior:

- read-only coordinator observation is wired to Home;
- Home says detected rather than protocol-connected;
- explicit Refresh performs the Stage 6A5 single-inventory decision;
- multi-device chooser behavior remains explicit;
- generic Fastboot remains behind extra confirmation;
- chooser selection is freshly revalidated before access;
- matching detach clears the Home observation;
- diagnostics and UI-visible state agree on the reviewed hardware campaign;
- transport remains unopened.

Verification status:

- exact-head CI: **PASS**;
- 120/120 tests: **PASS**;
- lint: **0 errors / 4 baseline warnings**;
- `assembleDebug`: **PASS**;
- post-CI hardware retest on F7 -> X3 Pro: **PASS** for Stage 6A5B invariants.

Stage 6A5B itself still did not implement transport. That gap was closed later by
Stage 6B1 at `b2c1af49...`. Still not implemented after the reviewed 6B1 boundary:

- generic ADB shell/service execution;
- Fastboot handshake;
- background retry loops;
- destructive operations.

The permanent legacy-parity and platform-change record for this stage is
`docs/USB_OBSERVATION_STAGE6A5.md`.

## 16. Completed Stage 6B2: fixed read-only ADB service probe

Stage 6B2 at exact commit `f31a2362384d61ce60268fa6058cdbe94fe2d3e9` is now
CI-verified and hardware-verified for one fixed identity query only:

`shell:getprop ro.product.device`

Exact-head CI passed 130/130 tests, lint with 0 errors / 4 known warnings, and
`assembleDebug`. Real-device diagnostics on the POCO X3 Pro patient observed the
expected stream sequence and returned exactly `vayu` twice across a physical
detach/reconnect cycle. See `docs/ADB_TRANSPORT_STAGE6B.md` for hashes and detailed
evidence.

There is still deliberately no generic `shell:` API. `SIDELOAD` and unknown peers do
not receive the probe, and probe failure does not create an automatic transport retry.

## 17. Completed Stage 6C1/6C2 Fastboot read-only foundation

Stage 6C1 at exact commit
`5b6b69f791663204a0abac8f32040fb5e3a1f9f6` is CI-verified and
hardware-verified for fixed `getvar:product` peer qualification.

Stage 6C2 at exact commit
`e1e33a45622a30d94b87c8187b3c8fcdf67f5c1d` is CI-verified and
hardware-verified for the fixed core command set: `current-slot`, `slot-count`,
`unlocked`, and `max-download-size`. The POCO X3 Pro bootloader returned
`unlocked=no`, `max-download-size=805306368`, and protocol FAIL/unreported for both
slot variables without breaking the session.

These earlier slices prove only their stated read-only boundaries. The current active
stage is recorded in Section 18.

## 18. Completed Stage 6C4 and active Stage 6C5 partition inventory

Stage 6C4 at exact commit
`118d31b1ad57689985880ccfd3f7829b9ae70679` is CI-verified and hardware-verified.
Reviewed exact-head CI passed 164/164 tests, lint with 0 errors / 4 known warnings,
and `assembleDebug`. Real hardware preserved the manual-only boundary: no broad query
was sent during automatic Fastboot connection, while each explicit UI request sent one
`getvar:all` on the existing healthy transport generation.

The first exported manual snapshot completed with `OKAY`, 222 parsed variables and 100
partition-metadata families. The cumulative campaign repeated the same snapshot after a
physical detach/reconnect and additional explicit user presses. Every broad response
payload was redacted from exported events, `serialReported=true` exposed only presence,
and no raw serial value appeared in diagnostics. USB Refresh remained a separate action
and continued to deduplicate the already healthy candidate.

The next allowed Fastboot slice is Stage 6C5: mechanically port the supplied legacy
`FastbootPartitionInventory` rules, but **derive only from the snapshot already obtained
by Stage 6C4**. Stage 6C5 sends no new Fastboot command. It may use the already collected
fixed product/current-slot/slot-count facts as supplemental non-sensitive context.

Required inventory invariants:

- only concrete size/type/is-logical evidence creates an inventory entry;
- `has-slot:<base>` is family metadata and never invents a partition by itself;
- POCO X3 Pro / `vayu` remains legacy A-only even if the bootloader exposes noisy A/B
  metadata; such contradiction is a warning, not authorization to synthesize suffixes;
- non-vayu A/B topology requires positive slot evidence; missing slot variables alone
  stay UNKNOWN;
- partition risk classification is informational only and never authorizes a write;
- duplicate/conflict warnings must not include raw variable values;
- the exported Stage 6C5 diagnostic event contains aggregate counts/topology only;
- no point query such as `partition-size:*`, `partition-type:*`, `is-logical:*`, or
  `has-slot:*` is added in Stage 6C5.

Stage 6C5 still does not add partition backfill probes, DATA/download,
flash/erase/set_active/reboot/OEM/unlock, or generic Fastboot command entry. Those remain
closed until the inventory model itself is independently CI- and hardware-verified.

## 19. Evidence vocabulary

Use these terms precisely:

- **CI-verified**: backed by reviewed CI reports for the exact commit;
- **hardware-observed**: seen in real-device diagnostics/screenshots;
- **hardware PASS**: only for the exact tested invariant/campaign;
- **NOT YET VERIFIED**: anything beyond the evidence boundary;
- **NOT IMPLEMENTED**: code path does not exist yet.

Never turn descriptor-level PASS into transport-level PASS.

## 20. Update protocol for this temporary file

After every meaningful stage:

1. update the active commit;
2. record the reviewed CI result only after reports are inspected;
3. add hardware evidence only after logs/ZIP/screenshots are reviewed;
4. move stable behavior rules into the permanent contract documents;
5. keep this file as a concise recovery checkpoint, not a second architecture spec.

If this file conflicts with a permanent contract, stop and resolve the conflict.
Do not silently choose whichever text is more convenient.

## 21. Removal checklist

Delete this temporary file when all of the following are true:

- all durable entry/USB/transport rules have permanent homes;
- hardware validation records have a durable final location;
- no active migration decision exists only here;
- the project no longer needs this file to reconstruct current development state;
- deletion happens before the stable/public release boundary.

Until then, update it deliberately rather than letting it drift.
