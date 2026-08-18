# Stage 6A3: entry-session gate and file-access separation

Stage 6A3 applies the explicitly approved A2 change that separates two concerns
that legacy onboarding coupled together:

1. acknowledgement of device-modification risk plus authorization of the current
   app-entry session;
2. access to firmware/images/ZIP/APK/diagnostic files.

The first remains an entry requirement. The second moves to the file workflow
that actually needs a file.

## Legacy mechanism

Pinned legacy `OnboardingGate` stored a versioned risk acknowledgement while
keeping `sessionAuthorized` only in memory. `MainActivity` could enter only when
that session was authorized, the acknowledgement was current, and legacy
`PermissionGate` reported mandatory storage access. A cold process therefore
returned to Welcome even when the risk checkbox had been accepted previously.

An exported USB attach could not bypass that gate. If attach launched
`MainActivity` before authorization, legacy redirected through Welcome and then
performed a normal startup scan instead of replaying the stale attach payload.

Android's USB host attach flow can grant device access when the user accepts a
matching attach launch. Stage 6A3 therefore treats Android USB permission and
NekoFlash entry authorization as separate facts: a platform grant must not start
NekoFlash USB processing while the entry session is still unauthorized.

## Approved A2 invariant

`EntrySessionGate` keeps the versioned-risk plus volatile-session part:

- persisted acknowledgement never authorizes a fresh process session;
- the user must actively keep the risk acknowledgement selected when entering;
- the first acknowledgement is durable only when persistence reports success;
- ending the entry session revokes only volatile authorization;
- changing the risk schema requires a new durable acknowledgement.

Stage 6A3B wires this boundary into Android. `NekoFlashApplication` owns the gate
and creates the coordinator without activating it. `MainActivity` starts USB
automation only after the gate authorizes the current entry.

## File-access change

Legacy required broad storage access before app entry. A2 no longer inherits
that coupling.

File access will be requested at the file workflow that needs it. The exact
choice between user-selected document access, an app-owned import/workspace, or
broader direct-path access is deliberately deferred until the large-file
flashing/sideload workflows are characterized. The selected mechanism must keep
transfer and failure semantics intact.

`MANAGE_EXTERNAL_STORAGE` is therefore not introduced by this stage and is not
an automatic legacy requirement. If later evidence shows that a core workflow
cannot be implemented correctly with narrower access, broad access requires a
separate documented decision.

## UI/process lifetime

Application-scoped USB ownership remains mandatory, but ownership and activity
are different concepts. An authorized UI entry activates the coordinator without
moving USB descriptor, permission, reconnect, or transport state into `MainActivity`.

Configuration recreation may preserve the entry session. Ordinary
non-configuration UI exit ends it. While A2 has no real `OperationCoordinator`
or active transport lease, that exit must also stop coordinator automation.
When long-running operations are later implemented, only an explicit
operation-owned lifecycle may keep USB active after UI exit.

## Stage split

Stage 6A3A (this patch):

- master-rule amendment;
- baseline/USB contract update;
- pure `EntrySessionGate`;
- deterministic entry-session regression tests;
- no Android gate UI and no runtime coordinator behavior change yet.

Stage 6A3B:

- SharedPreferences persistence adapter;
- RU/EN Compose entry screen;
- gate-before-USB handling in `MainActivity`;
- coordinator start only after authorization;
- coordinator stop/cleanup on ordinary session exit;
- pre-gate attach normalization followed by safe startup enumeration;
- same-process re-entry regression coverage for the volatile gate.

### Android lifecycle wiring

A fresh unauthorized Activity does not start the coordinator and does not forward
USB attach Intents. If the user authorizes the entry, the coordinator starts and
any attach payload that arrived before authorization is marked consumed without
reading its device extra. `onActivityCreated` then takes the normal consumed-intent
path and schedules the existing `350 ms` startup enumeration.

While the session is authorized, configuration recreation keeps both the volatile
gate and coordinator active; the replacement Activity receives a fresh UI-entry
generation. Ordinary non-configuration destruction ends the gate and stops the
coordinator. Because no protocol transport exists yet, stop cancels startup and
mode-switch callbacks, cancels permission timeouts, clears pending permission and
current descriptor state, and unregisters both dynamic receivers.

The coordinator also ignores Activity callbacks, startup ticks, mode-switch ticks,
and access requests while inactive. This is a fail-closed entry boundary, not a
new USB candidate or permission-result rule.

## Verification status

Stage 6A3B can be verified by pure regression tests plus Android compile/lint/build.
It still cannot prove OEM USB delivery, permission dialogs, attach normalization,
or real re-enumeration.

A2 hardware status remains **NOT YET VERIFIED**.
