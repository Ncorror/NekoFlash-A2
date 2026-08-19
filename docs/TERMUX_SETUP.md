# Termux setup

Termux is the Git/GitHub client for NekoFlash-A2. Android builds are produced by GitHub Actions.

## Install the required tools

```bash
pkg update -y
pkg upgrade -y
pkg install git gh openssh zip unzip -y
```

Do not install Android Studio, Android SDK, a local Gradle toolchain, emulator, desktop adb, or desktop fastboot for this workflow.

## Configure Git identity

```bash
git config --global user.name "Ncorror"
git config --global user.email "rastaxd1102@gmail.com"
git config --global init.defaultBranch main
```

## Clean GitHub re-authentication

Use this only when authentication must be reset deliberately:

```bash
gh auth logout --hostname github.com --user Ncorror || true
gh auth login --hostname github.com --git-protocol https --web
gh auth switch --hostname github.com --user Ncorror
gh auth setup-git --hostname github.com
gh auth status --active --hostname github.com
```

`gh auth logout` removes the stored local authentication configuration. It does not revoke GitHub CLI OAuth tokens on other devices.

## Clone the repository

```bash
cd ~
gh repo clone Ncorror/NekoFlash-A2
cd NekoFlash-A2
```

## Install the single push helper

From the repository root:

```bash
rm -f "$PREFIX/bin/gpush"
install -m 0755 scripts/gpush "$PREFIX/bin/gpush"
```

There is only one project push helper: `scripts/gpush` (installed as `gpush`).
It does not stage files and does not create commits. It verifies GitHub authentication,
repository identity, a clean working tree, and remote divergence before pushing.
If GitHub authentication is missing or invalid, it starts the normal browser login flow.

## Canonical assistant ↔ Termux workflow

This section is the binding operator contract for chat-assisted changes. A new or recovered chat must read this section before giving Termux commands that can change the repository, create/publish recovery data, or select CI evidence. Do not invent an alternative workflow merely because a different shell command would also work.

Canonical identities and helpers:

- repository root: `~/NekoFlash-A2`;
- GitHub repository: `Ncorror/NekoFlash-A2`;
- push helper: `./scripts/gpush` (the installed `gpush` command is the same helper);
- recovery creation: `./scripts/recovery-bundle`;
- verified recovery publication: `./scripts/recovery-publish`.

Authentication rules:

- do not log out or reset GitHub authentication during normal work;
- use the existing authenticated `gh` session while it is valid;
- only run the clean re-authentication procedure above when authentication is actually invalid or the user explicitly requests a reset;
- never place GitHub tokens, `hosts.yml`, credentials, or other secrets into patches, recovery bundles, chat artifacts, or the repository.

### Standard delivery for an assistant-produced repository change

The default handoff is **one install ZIP**, not an improvised multi-command paste and not a full source-tree replacement. The ZIP contains exactly:

```text
<change>-install.zip
├── APPLY_IN_TERMUX.sh
├── <change>.patch
└── README.txt
```

The user downloads that one ZIP to Android `Downloads`, then runs one launcher command with the actual package names supplied by the assistant:

```bash
cd ~/storage/downloads && \
rm -rf <install-dir> && \
unzip -o <install-zip> && \
bash <install-dir>/APPLY_IN_TERMUX.sh
```

The assistant must not assume a chat-generated file already exists under `~/storage/downloads`; the file must first be provided to and downloaded by the user.

`APPLY_IN_TERMUX.sh` must:

1. resolve its absolute `SCRIPT_DIR` before any `cd`;
2. enter `~/NekoFlash-A2`;
3. require the expected branch and a clean working tree;
4. verify repository identity and GitHub authentication;
5. run `git fetch origin`;
6. verify both local HEAD and `origin/<branch>` against the exact reviewed baseline encoded in the installer;
7. verify the reviewed patch SHA-256;
8. run `git apply --check` before applying it;
9. apply only that reviewed patch;
10. verify the complete changed-file set, including untracked files;
11. run `git diff --check`;
12. run syntax checks such as `bash -n` for changed shell scripts when applicable;
13. stage only the exact reviewed files, never `git add .`;
14. verify the staged-file set and `git diff --cached --check`;
15. create exactly one intended commit;
16. push through `./scripts/gpush`;
17. fetch again and verify local HEAD equals the remote branch;
18. finish with a clean working tree.

If an error occurs before commit, the installer should restore only the files it changed. If commit succeeds but push fails, preserve the local commit and report the failure; do not destroy history automatically.

Do not create a second push helper, a replacement recovery helper, or a new authentication wrapper when the tracked project scripts already cover the operation. If this canonical workflow cannot perform a genuinely required task, state the exact gap before proposing a deviation.

A full source ZIP may be supplied for review or disaster recovery, but ordinary repository mutations should use the reviewed patch/install-ZIP path above so Git records the exact change.

## Daily Git workflow

```bash
cd ~/NekoFlash-A2
git status
git pull --ff-only

# edit files or apply a reviewed patch

git diff --check
git diff

git add <exact-files-to-commit>
git diff --cached --check
git diff --cached
git commit -m "small verified change"
gpush
```

Do not use `git add .` for migration stages. Stage the exact reviewed files.

## Recovery bundle for interrupted chats

The repository checkpoint records the meaning of the current migration state, while Git records history.
Do not hard-code the current repository HEAD into the checkpoint merely to make a chat backup; that creates recursive documentation-only commits.

For chat continuity, use the tracked `scripts/recovery-bundle` helper. It creates one ZIP outside the repository and never stages, commits, pushes, or edits project files.

Create a recovery bundle only from a clean, synchronized checkout. After a meaningful stage has been committed, pushed, and its evidence has been reviewed, run:

```bash
cd ~/NekoFlash-A2
./scripts/recovery-bundle
```

If the reviewed CI report or hardware diagnostics should travel with the recovery snapshot, pass them explicitly:

```bash
./scripts/recovery-bundle \
  ~/storage/downloads/NekoFlash-<commit>-reports.zip \
  ~/storage/downloads/NekoFlash-A2-diagnostics-<timestamp>.zip
```

Only files explicitly named on the command line are copied as extra evidence. GitHub credentials, `.git`, ignored files, untracked files, and the Termux environment are not copied.

The generated ZIP is written to `~/storage/downloads` by default and contains:

- an exact `git archive` snapshot of the committed HEAD;
- `PROJECT_STATE.txt` with repository, branch, HEAD, checkpoint identity, and hashes;
- recent Git history and current Git status;
- GitHub Actions metadata for the exact HEAD and recent runs;
- `ci/artifacts.json` and `ci/artifacts.txt` with original GitHub artifact IDs, names, sizes, expiry metadata, and server-reported SHA-256 digests for exact-HEAD workflow runs;
- `CHAT_RECOVERY_PROMPT.md` with the mandatory restore order for a new chat;
- `SHA256SUMS.txt` covering every file inside the recovery bundle;
- any explicitly supplied evidence files, plus `evidence/EVIDENCE_SHA256.txt` with hashes of those local evidence files.

The GitHub artifact digest and the hash of a locally supplied evidence ZIP are intentionally recorded separately. A file downloaded with `gh run download` and then repacked is a new ZIP and is not assumed to be byte-identical to GitHub's original artifact archive.

The helper refuses to create an official recovery bundle when the working tree is dirty or when local HEAD differs from `origin/<current-branch>`.
This keeps every recovery ZIP tied to a reproducible Git state rather than an accidental local edit.

When a chat is interrupted, upload the newest recovery ZIP first. The new chat must follow `CHAT_RECOVERY_PROMPT.md` and reconstruct the project state before proposing or changing code.
The recovery ZIP is a convenience backup, not a replacement for Git history, permanent behavior contracts, CI artifacts, or hardware evidence.

## Publish a verified recovery to GitHub Releases

A recovery ZIP is not safely archived merely because it exists in `~/storage/downloads`. After the bundle has been reviewed and declared VERIFIED, publish that exact ZIP to GitHub Releases with its verified SHA-256:

```bash
cd ~/NekoFlash-A2
./scripts/recovery-publish \
  ~/storage/downloads/NekoFlash-A2-recovery-<timestamp>-<sha>.zip \
  <verified-64-character-sha256>
```

The publisher:

- requires the already reviewed SHA-256 and refuses a mismatch;
- verifies the bundle's internal `SHA256SUMS.txt` and evidence hashes;
- verifies the bundled HEAD exists in `Ncorror/NekoFlash-A2`;
- creates a dedicated `recovery-<12-char-commit>` prerelease targeted at that exact commit;
- uploads both the recovery ZIP and a `.sha256` checksum asset;
- never overwrites an existing recovery tag/release;
- downloads the published asset again and verifies its SHA-256 before reporting success.

Recovery releases are deliberately marked as prereleases and `Latest=false` so they are not confused with application releases. They provide an off-device copy of the VERIFIED recovery ZIP and are not subject to the normal GitHub Actions artifact retention workflow. A release can still be removed manually, so keeping an additional offline copy remains sensible.

If the local recovery ZIP is lost, list releases and download the required recovery tag:

```bash
gh release list --repo Ncorror/NekoFlash-A2 --limit 50

mkdir -p ~/storage/downloads/nekoflash-recovery-restore
gh release download recovery-<12-char-commit> \
  --repo Ncorror/NekoFlash-A2 \
  --pattern 'NekoFlash-A2-recovery-*.zip' \
  --pattern '*.sha256' \
  --dir ~/storage/downloads/nekoflash-recovery-restore

cd ~/storage/downloads/nekoflash-recovery-restore
sha256sum -c *.sha256
```

Only a recovery bundle that has already passed review should be published. Creating a bundle and publishing it are intentionally separate steps.

## GitHub Actions

CI must be selected by the **exact committed HEAD**, not by whichever run happens to be newest. Start from a clean synchronized checkout and derive identifiers dynamically:

```bash
cd ~/NekoFlash-A2
git fetch origin

HEAD="$(git rev-parse HEAD)"
REMOTE="$(git rev-parse origin/main)"

[ "$HEAD" = "$REMOTE" ] || {
  echo "ERROR: local HEAD and origin/main differ"
  exit 1
}

RUN_ID="$(
  gh run list \
    --commit "$HEAD" \
    --limit 1 \
    --json databaseId \
    --jq '.[0].databaseId // empty'
)"

[ -n "$RUN_ID" ] || {
  echo "ERROR: no GitHub Actions run found for $HEAD"
  exit 1
}

RUN_HEAD="$(gh run view "$RUN_ID" --json headSha --jq '.headSha')"
[ "$RUN_HEAD" = "$HEAD" ] || {
  echo "ERROR: selected run does not belong to HEAD $HEAD"
  exit 1
}

echo "HEAD=$HEAD"
echo "RUN_ID=$RUN_ID"

gh run watch "$RUN_ID" --exit-status
```

Inspect that exact run when needed:

```bash
gh run view "$RUN_ID"
gh run view "$RUN_ID" --log-failed
```

Download and repack only the `-reports` artifact from that same run:

```bash
mapfile -t REPORT_ARTIFACTS < <(
  gh api "repos/Ncorror/NekoFlash-A2/actions/runs/$RUN_ID/artifacts?per_page=100" \
    --jq '.artifacts[] | select(.name | endswith("-reports")) | .name'
)

[ "${#REPORT_ARTIFACTS[@]}" -eq 1 ] || {
  echo "ERROR: expected exactly one reports artifact, found ${#REPORT_ARTIFACTS[@]}"
  exit 1
}

SHORT_HEAD="${HEAD:0:7}"
REPORT_DIR="$HOME/storage/downloads/NekoFlash-${SHORT_HEAD}-reports"
REPORT_ZIP="$HOME/storage/downloads/NekoFlash-${SHORT_HEAD}-reports.zip"

rm -rf "$REPORT_DIR"
rm -f "$REPORT_ZIP"
mkdir -p "$REPORT_DIR"

gh run download "$RUN_ID" \
  --name "${REPORT_ARTIFACTS[0]}" \
  --dir "$REPORT_DIR"

(
  cd "$REPORT_DIR"
  zip -q -r "$REPORT_ZIP" .
)

sha256sum "$REPORT_ZIP"
```

The SHA-256 of this locally repacked reports ZIP is a local evidence hash and is not assumed to equal the original GitHub Actions artifact digest. `scripts/recovery-bundle` preserves both values separately.

When a new chat provides Termux commands, commit SHAs, run IDs, artifact names, and recovery filenames must be derived from the current live Git/recovery state. Do not reuse stale identifiers copied from an older chat merely because the command shape is still correct.

CI success proves only the automated checks. USB/ADB/Fastboot behavior remains `NOT YET VERIFIED` until the relevant hardware test is performed.
