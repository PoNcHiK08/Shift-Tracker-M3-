#!/usr/bin/env bash
# Runs the app's emulator tests on the Firestore and Auth emulators: FirestoreEmulatorTest (the Firestore store
# and the sync engine against firestore.rules) and CloudAuthEmulatorTest (Firebase sign-in up to Settings).
# Needs Node 20+ and Java 21.
# The rules tests alone: cd firebase/rules-test && npm ci && npm test
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
root="$(dirname "$here")"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

# The committed rules let nobody in (placeholder email); the tests sign in as owner@example.com.
sed 's/YOUR_EMAIL_HERE/owner@example.com/g' "$here/firestore.rules" > "$work/firestore.rules"
cp "$here/firebase.json" "$work/firebase.json"

cd "$work"
npx --yes firebase-tools@15.34.0 emulators:exec --only firestore,auth --project demo-shift-tracker \
  "cd '$root' && ./gradlew :app:testDebugUnitTest --tests '*EmulatorTest' --rerun ${GRADLE_ARGS:-} $*"
