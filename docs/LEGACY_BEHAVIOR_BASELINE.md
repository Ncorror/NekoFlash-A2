# NekoFlash A2 — Legacy Behavior Baseline

## Source of truth

Repository:

Ncorror/NekoFlash

Baseline commit:

c49242c771dac9d147597c0d07e9ac1c6d320254

This commit is the executable behavioral reference for the A2 clean rewrite.

## Protected behavior

The following behavior must not change without explicit evidence,
regression coverage, risk analysis, and hardware validation:

- USB discovery and lifecycle
- USB permission handling
- interface and endpoint selection
- attach / detach / reconnect behavior
- native USBFS behavior
- ADB packet framing and checksum behavior
- ADB authentication and service semantics
- Fastboot command / response semantics
- Fastboot DATA / INFO / OKAY / FAIL handling
- partition and slot handling
- ADB Sideload transfer semantics
- DONEDONE handling
- recovery-side install verification
- Xiaomi account/session behavior
- Mi Unlock protocol behavior
- cancellation and destructive-operation behavior

## Legacy implementation inventory

### ADB

- AdbProtocol.kt
- AdbPacketChecksum.kt
- AdbPacketDispatcher.kt
- AdbServiceCompletionPolicy.kt
- AdbKeyStore.kt

### Fastboot

- FastbootProtocol.kt
- FastbootGetVarAllParser.kt
- FastbootPartitionInventory.kt
- FastbootPartitionProbePlanner.kt
- FastbootValueParser.kt
- PartitionNameResolver.kt

### USB

- NativeUsbfsBackend.kt
- NativeTransferProgress.kt
- UsbDeviceInspector.kt
- UsbSessionSnapshot.kt
- UsbTransportShutdownPolicy.kt
- native_usbfs.cpp

### Sideload / Recovery

- AdbProtocol.kt
- RecoveryInstallVerifier.kt

### Xiaomi / Mi Unlock

- MiAccountClient.kt
- MiAccountSecurityPolicy.kt
- MiUnlockClient.kt

## Migration rule

Do not copy the legacy architecture wholesale.

In particular:

- do not port DeviceViewModel as the new USB owner
- do not port MainActivity orchestration
- do not move raw USB ownership into UI
- do not redesign protocol behavior while extracting it

Migration order:

legacy behavior -> regression contract -> A2 implementation -> CI -> hardware verification

Until hardware verification exists, protocol compatibility status is:

NOT YET VERIFIED
