# Termux setup

Termux is the Git/GitHub client for NekoFlash-A2. Android builds are produced by GitHub Actions.

## Install the required tools

```bash
pkg update -y
pkg upgrade -y
pkg install git gh openssh -y
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
