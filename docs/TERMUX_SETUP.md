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

List recent runs:

```bash
gh run list --limit 10
```

Watch the newest run and return a failing exit code if CI fails:

```bash
gh run watch --exit-status
```

Inspect a failed run:

```bash
gh run view --log-failed
```

Download artifacts:

```bash
mkdir -p ~/downloads/nekoflash-ci
gh run download --dir ~/downloads/nekoflash-ci
```

CI success proves only the automated checks. USB/ADB/Fastboot behavior remains `NOT YET VERIFIED` until the relevant hardware test is performed.
