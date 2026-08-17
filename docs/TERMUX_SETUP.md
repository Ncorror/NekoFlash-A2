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

## Authenticate GitHub

```bash
gh auth login --hostname github.com --git-protocol https --web
gh auth switch --hostname github.com --user Ncorror
gh auth setup-git --hostname github.com
gh auth status --active --hostname github.com
```

## Clone the repository

```bash
cd ~
gh repo clone Ncorror/NekoFlash-A2
cd NekoFlash-A2
```

## Install the push helper

From the repository root:

```bash
install -m 0755 scripts/gpush "$PREFIX/bin/gpush"
```

`gpush` never stores a password or token. Authentication stays with GitHub CLI.

## Daily Git workflow

```bash
git status
git pull --ff-only
git switch -c a2/<small-change>
# edit files
git add .
git commit -m "small verified change"
gpush
```

## GitHub Actions

List recent runs:

```bash
gh run list --limit 10
```

Watch a run:

```bash
gh run watch
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
