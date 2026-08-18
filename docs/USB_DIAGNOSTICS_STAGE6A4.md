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
archive is saved. It is only the evidence serializer and leaves the caller-owned
output stream open.

## Stage 6A4B: Android document export

Stage 6A4B wires the authorized Home UI to Android's document-creation contract
with MIME type `application/zip`. The system picker chooses the destination and
returns a `content://` URI. A non-content result is rejected before any write.
No broad shared-storage permission or direct shared-storage path is introduced.

`MainActivity` owns only the Activity Result/document boundary. It asks the
Application-scoped `UsbSessionCoordinator` for the suggested archive name and to
write the current diagnostics run. UI never enumerates USB, reads descriptors,
or reaches into the diagnostics directory itself.

The `UsbDiagnosticStore` serializes event/snapshot writes and construction of a
finished local ZIP snapshot through one private I/O lock. An export therefore
cannot capture the JSON half of a snapshot pair while the matching text half is
still being written. After that local snapshot is finished, the lock is released
before its bytes are copied to the user-selected document, so a slow document
provider cannot block later USB event recording. Temporary archive cleanup runs in
a `finally` block and the temporary file lives outside the current run directory.
The lock changes diagnostics I/O only and does not gate USB state machines.

If the document picker returns after the volatile entry session has ended, A2
refuses to write the archive and asks the user to enter again. Provider opening,
archive transfer, and flush run on a one-shot background executor so DocumentsUI
or a remote provider cannot perform file I/O on the main thread. The export button
is disabled for the active request. A provider/open/write failure is reported in
the UI; the selected destination may contain a partial file, so A2 does not claim
a successful export unless the transfer and flush return normally.

## Verification boundary

Stage 6A4A CI proves the pure archive format. Stage 6A4B adds Android compile,
resource-parity and UI wiring coverage plus a JVM regression that the exporter
does not close its caller-owned stream. CI still cannot prove a real DocumentsUI
provider, OEM USB delivery, permission dialogs, attach normalization, or actual
hardware evidence collection.

A2 hardware status remains **NOT YET VERIFIED** until the exported ZIP is collected
from a real Android-host USB validation run.
