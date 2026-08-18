# USB diagnostics export - Stage 6A4

Stage 6A4 prepares diagnostics collection for the first safe A2 hardware test.
It does not open USB transport, claim an interface, perform an ADB/Fastboot
handshake, or change any USB selection/permission/re-enumeration rule.

## Stage 6A4A: current-run archive foundation

Earlier diagnostics files lived directly under one persistent app-specific
`diagnostics` directory. That made a future whole-directory ZIP ambiguous because
old process evidence could be mixed with the process currently under test.

`UsbDiagnosticStore` now creates one unique child directory per process store:

```text
diagnostics/
  usb-run-<epoch>-<uuid>/
    usb-events.txt
    usb-session-<sessionId>.json
    usb-session-<sessionId>.txt
```

`directoryPath()` returns only the active run child. Existing root-level files or
older run directories remain untouched and are not part of the current export.
This changes diagnostics storage organization only; it does not mutate USB state.

`UsbDiagnosticsZipExporter` is pure JVM code. It accepts one run directory and
writes:

- `export-manifest.txt` with schema, UTC export time, source file count/bytes and
  the included filenames;
- `usb-events.txt` when present;
- `usb-session-*.json` and `usb-session-*.txt` when present.

Unrelated files are deliberately excluded. Archive entry order is deterministic
and filesystem modification timestamps are not copied into ZIP entries. The
suggested filename is:

```text
NekoFlash-A2-diagnostics-YYYYMMDD-HHMMSSZ.zip
```

Archive schema:

```text
io.github.ncorror.nekoflash.usb-diagnostics-export.v1
```

The exporter does not request storage permission and does not decide where the
archive is saved. It is only the evidence serializer.

## Stage 6A4B: Android export wiring

Only after Stage 6A4A CI is green, the authorized UI will use Android's document
creation flow to let the user choose the ZIP destination. The UI will trigger the
export but will not enumerate USB devices, inspect descriptors, or own protocol
state. No broad shared-storage permission is introduced for diagnostics export.

## Verification boundary

Stage 6A4A can verify run isolation source structure and ZIP serialization through
JVM/Android compilation plus unit tests. It cannot verify DocumentsUI behavior or
real hardware evidence collection.

A2 hardware status remains **NOT YET VERIFIED**.
