#!/usr/bin/env bash
# Fails when an APK built with google-services.json lacks what cloud sync looks up by name at runtime, so the
# shrinkers of a release build (R8 and isShrinkResources) can remove it without the build noticing:
#   - the strings google_app_id (Firebase starts from it) and default_web_client_id (Google sign-in; without it
#     Settings says "Not available in this build");
#   - the class Credential Manager loads to reach Google sign-in (CredentialProviderPlayServicesImpl).
# Usage: .github/scripts/check-apk-cloud-sync.sh APK...   (needs aapt2 from the Android SDK's build-tools)
set -euo pipefail

sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
aapt2=$(ls -d "$sdk"/build-tools/*/ 2> /dev/null | sort -V | tail -1)aapt2
[ -x "$aapt2" ] || { echo "::error::aapt2 not found (set ANDROID_HOME to the Android SDK)."; exit 2; }
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

failed=0
for apk in "$@"; do
  name=$(basename "$apk")
  resources=$("$aapt2" dump resources "$apk")
  for string in google_app_id default_web_client_id; do
    value=$(printf '%s\n' "$resources" | grep -A1 -E "string/$string( |\$)" | sed -n 's/^[[:space:]]*() "\(.*\)"$/\1/p' | head -1 || true)
    if [ -z "$value" ]; then
      echo "::error::$name has no string $string, so cloud sync is unavailable in it: it was built without google-services.json, or a shrinker removed the string (keep it in app/src/main/res/raw/cloud_sync_keep.xml)."
      failed=1
    else
      echo "$name: string $string present"
    fi
  done
  rm -rf "$work/dex" && mkdir "$work/dex"
  unzip -q -o "$apk" 'classes*.dex' -d "$work/dex"
  if grep -aqF 'Landroidx/credentials/playservices/CredentialProviderPlayServicesImpl;' "$work"/dex/classes*.dex; then
    echo "$name: Credential Manager's Google provider present"
  else
    echo "::error::$name has no androidx.credentials.playservices.CredentialProviderPlayServicesImpl: Google sign-in can't work in it."
    failed=1
  fi
done
exit $failed
