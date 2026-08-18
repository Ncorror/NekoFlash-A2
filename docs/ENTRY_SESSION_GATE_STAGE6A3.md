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

USB activation is not part of Stage 6A3A yet. The next wiring stage must ensure
that no coordinator scan, permission request, attach processing, reconnect, or
mode-switch automation occurs before `EntrySessionGate` is authorized.

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
are different concepts. A future authorized UI entry may activate the
coordinator without moving USB state into `MainActivity`.

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

Stage 6A3B (only after 6A3A CI is green):

- SharedPreferences persistence adapter;
- RU/EN Compose entry screen;
- gate-before-USB handling in `MainActivity`;
- coordinator start only after authorization;
- coordinator stop/cleanup on ordinary session exit;
- pre-gate attach normalization followed by safe startup enumeration.

## Verification status

Stage 6A3A can prove only the pure entry-session contract. It cannot prove
Android lifecycle delivery or real USB behavior.

A2 hardware status remains **NOT YET VERIFIED**.
