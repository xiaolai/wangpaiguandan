#!/usr/bin/env bash
# Local CI. This repo has no git remote, so .github/workflows/verify.yml never runs.
# This script runs the same checks on this machine. Any failing step stops the run.
set -euo pipefail

repo="$(cd "$(dirname "$0")/.." && pwd)"
cd "$repo"

step() { printf '\n== %s\n' "$1"; }

step "Clean-room guard (no tracked or new source names the upstream package)"
pathspecs=('*.kt' '*.kts' '*.java' '*.swift' '*.xml' '*.py' '*.toml' '*.properties' '*.gradle' '*.pro' '*.json')
needle='page.codeberg.lanticy'
# Tracked and untracked (not ignored) files: locally, new files must be checked before they are
# committed. The scan greps exactly the files counted here (git grep alone skips untracked files).
guard_files=()
while IFS= read -r -d '' f; do
  [ -f "$f" ] && guard_files+=("$f") # a tracked file deleted in the work tree has nothing to scan
done < <(git ls-files -z --cached --others --exclude-standard -- "${pathspecs[@]}")
count=${#guard_files[@]}
kotlin=$(printf '%s\n' "${guard_files[@]}" | grep -c '\.kt$' || true)
if [ "$count" -eq 0 ] || [ "$kotlin" -eq 0 ]; then
  echo "clean-room guard would scan $count files ($kotlin Kotlin); refusing to pass an empty scan" >&2
  exit 1
fi
# Positive control: the same scanner must find the needle in a planted file.
planted=$(mktemp)
printf 'package %s.planted\n' "$needle" > "$planted"
grep -q -F "$needle" -- "$planted" || { rm -f "$planted"; echo "clean-room scanner missed a planted match" >&2; exit 1; }
rm -f "$planted"
status=0
grep -n -F "$needle" -- "${guard_files[@]}" || status=$?
case "$status" in
  0) echo "upstream package found in source (listed above)" >&2; exit 1 ;;
  1) echo "scanned $count files ($kotlin Kotlin), no matches" ;;
  *) echo "grep failed with exit $status" >&2; exit "$status" ;;
esac

step "Design import and token parity"
python3 tools/verify_design_import.py

step "Golden rule cases: regenerate, then audit (audit fails on any FAIL)"
python3 -I tools/golden/build.py "$repo"
python3 -I tools/golden/audit.py "$repo" | grep -E "^(cases|FAIL|consistency problems|quotes checked)"

step "iPhone Swift core skeleton (G3, ios/GuandanCore): builds, and its conformance tests read the shared fixtures"
# The rules engine is not ported (WI-11); these tests only check that every shared fixture is found and parses.
if command -v swift >/dev/null 2>&1; then
  swift_log=$(mktemp)
  (cd ios/GuandanCore && swift build -Xswiftc -warnings-as-errors && swift test -Xswiftc -warnings-as-errors) > "$swift_log" 2>&1 || { cat "$swift_log" >&2; rm -f "$swift_log"; echo "swift build or test failed in ios/GuandanCore" >&2; exit 1; }
  summary=$(grep -E "Executed [0-9]+ tests?, with [0-9]+ tests? skipped and 0 failures" "$swift_log" | tail -1 || true)
  rm -f "$swift_log"
  [ -n "$summary" ] || { echo "swift test printed no XCTest summary with 0 failures" >&2; exit 1; }
  echo "ios/GuandanCore:${summary}"
else
  echo "SKIPPED: no Swift toolchain on this machine (ios/GuandanCore not built)"
fi

step "Android and JVM build, tests and lint"
# Gradle 8.14.6 does not run on JDK 27. Use JDK 21 when it is installed.
if [ -z "${JAVA_HOME:-}" ] && [ -d /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ]; then
  export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
fi
# WI-12: :app:assembleRelease is the minified (R8) release build, unsigned. Output is kept to check R8's warnings.
gradle_log=$(mktemp)
trap 'rm -f "$gradle_log"' EXIT
./gradlew :core:test :session:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :app:assembleDebugAndroidTest :app:assembleRelease 2>&1 | tee "$gradle_log"

step "Release build (WI-12): minified, gated, R8 reads the Kotlin metadata"
release_apk=app/build/outputs/apk/release/app-release-unsigned.apk
[ -f "$release_apk" ] || { echo "release APK not found at $release_apk" >&2; exit 1; }
[ -f app/build/outputs/mapping/release/mapping.txt ] || { echo "no R8 mapping for the release build: minify did not run" >&2; exit 1; }
grep -q 'minifyReleaseWithR8' "$gradle_log" || { echo "the release build did not run R8 (task minifyReleaseWithR8 absent from the log)" >&2; exit 1; }
# An R8 older than the Kotlin compiler cannot read its metadata (gradle/libs.versions.toml, r8 pin).
if grep -i 'error occurred when parsing kotlin metadata' "$gradle_log"; then
  echo "R8 cannot read this Kotlin version's metadata: update the r8 pin in gradle/libs.versions.toml" >&2
  exit 1
fi
release_config=$(find app/build/generated/source/buildConfig/release -name BuildConfig.java)
grep -q 'RELEASE_GATE = true' "$release_config" || { echo "release BuildConfig does not set RELEASE_GATE = true ($release_config)" >&2; exit 1; }
echo "release APK $(wc -c < "$release_apk" | tr -d ' ') bytes, R8 mapping present, RELEASE_GATE = true"

step "Distribution gate (WI-12): refuses a signed release while a profile is unsigned or the project license is unrecorded"
# Negative control first: the same gate, pointed at unsigned copies of the packaged profiles, must refuse and name each one.
unsigned_dir=$(mktemp -d)
trap 'rm -f "$gradle_log"; rm -rf "$unsigned_dir"' EXIT
for f in rules/profiles/*.json; do
  sed -e 's/"provisional": *false/"provisional": true/' "$f" > "$unsigned_dir/$(basename "$f")"
  grep -q '"provisional": true' "$unsigned_dir/$(basename "$f")" || { echo "could not make an unsigned copy of $f" >&2; exit 1; }
done
control_status=0
control_out=$(./gradlew -q :app:verifyReleaseDistributable -Pwpgd.gateProfilesDir="$unsigned_dir" 2>&1) || control_status=$?
[ "$control_status" -ne 0 ] || { echo "the distribution gate passed unsigned profiles (negative control)" >&2; exit 1; }
for f in "$unsigned_dir"/*.json; do
  id=$(basename "$f" .json)
  printf '%s\n' "$control_out" | grep -q "rule profile $id .* is provisional" || { echo "the distribution gate did not name unsigned $id (negative control)" >&2; printf '%s\n' "$control_out" >&2; exit 1; }
done
echo "negative control: the gate refuses unsigned copies of $(ls "$unsigned_dir" | wc -l | tr -d ' ') profiles"

provisional=$(grep -l '"provisional": *true' rules/profiles/*.json || true)
license_open=no
if grep -q '^\*\*Status: OWNER DECISION REQUIRED\*\*$' release/PROJECT-LICENSE.md || ! grep -qE '^SPDX-License-Identifier: [^ ]+$' release/PROJECT-LICENSE.md; then license_open=yes; fi
gate_status=0
gate_out=$(./gradlew -q :app:verifyReleaseDistributable 2>&1) || gate_status=$?
if [ -n "$provisional" ] || [ "$license_open" = yes ]; then
  [ "$gate_status" -ne 0 ] || { echo "the distribution gate passed with provisional profiles or no project license" >&2; exit 1; }
  for f in $provisional; do
    id=$(basename "$f" .json)
    printf '%s\n' "$gate_out" | grep -q "rule profile $id " || { echo "the distribution gate did not name $id" >&2; printf '%s\n' "$gate_out" >&2; exit 1; }
  done
  if [ "$license_open" = yes ]; then
    printf '%s\n' "$gate_out" | grep -q 'project license not recorded' || { echo "the distribution gate did not flag the project license" >&2; exit 1; }
    echo "PROJECT LICENSE: OWNER DECISION REQUIRED (release/PROJECT-LICENSE.md)"
  fi
  echo "distribution gate refuses, as it must:"
  printf '%s\n' "$gate_out" | grep '^ *- ' || true
else
  [ "$gate_status" -eq 0 ] || { echo "the distribution gate failed although every profile is signed and the license is recorded" >&2; printf '%s\n' "$gate_out" >&2; exit 1; }
  echo "distribution gate passes: signed profiles with signed decisions, project license $(sed -n 's/^SPDX-License-Identifier: //p' release/PROJECT-LICENSE.md) recorded"
fi
# What still stands between this tree and a signed release: the key (app/build.gradle.kts, WI-12).
missing_keys=()
for k in STORE_FILE STORE_PASSWORD KEY_ALIAS KEY_PASSWORD; do
  v="WPGD_RELEASE_$k"
  if [ -z "${!v:-}" ] && ! { [ -f release-signing.properties ] && grep -qE "^$k=.+" release-signing.properties; }; then missing_keys+=("$v"); fi
done
if [ "${#missing_keys[@]}" -gt 0 ]; then
  echo "RELEASE BLOCKED: no release signing key on this machine (missing ${missing_keys[*]}; or release-signing.properties). Device and human procedures HP-1..HP-8 are also still owed (dev-docs/evidence/)."
else
  echo "release signing key values present (not used by this script; build with -Pwpgd.releaseSigning=true)"
fi

step "r8check (WI-12): the minified check build and its on-device test APK build"
# Running them needs a device: ./gradlew -Pwpgd.testBuildType=r8check :app:connectedR8checkAndroidTest \
#   -Pandroid.testInstrumentationRunnerArguments.class=cn.wpgd.app.R8SerializationTest
./gradlew -Pwpgd.testBuildType=r8check :app:assembleR8check :app:assembleR8checkAndroidTest

allow=app/permissions-allowlist.txt
[ -f "$allow" ] || { echo "allow-list not found at $allow" >&2; exit 1; }
# Untagged lines: every build. "bleTransport:" lines: only the flag-on build (WI-10).
base=$(sed -e 's/#.*//' "$allow" | grep -v '^[[:space:]]*bleTransport:' | sed -e 's/[[:space:]]//g' | grep -v '^$' | sort || true)
ble=$(sed -e 's/#.*//' "$allow" | grep '^[[:space:]]*bleTransport:' | sed -e 's/^[[:space:]]*bleTransport://' -e 's/[[:space:]]//g' | grep -v '^$' | sort || true)
[ -n "$base" ] || { echo "the allow-list $allow names no permission; refusing an empty comparison" >&2; exit 1; }
[ -n "$ble" ] || { echo "the allow-list $allow names no bleTransport permission; refusing an empty comparison" >&2; exit 1; }

# check_manifest <merged manifest> <expected sorted names> <label>
check_manifest() {
  local merged="$1" expected="$2" label="$3" actual
  [ -f "$merged" ] || { echo "merged manifest not found at $merged" >&2; exit 1; }
  if grep -n -E '<(permission|uses-permission-sdk-23)\b' "$merged"; then
    echo "the $label merged manifest declares a permission or uses uses-permission-sdk-23 (listed above)" >&2
    exit 1
  fi
  # Elements may span lines in the merged file: flatten before matching.
  actual=$(tr '\n' ' ' < "$merged" | grep -o -E '<uses-permission[^>]*android:name="[^"]+"' | sed -E 's/.*android:name="([^"]+)"/\1/' | sort || true)
  if [ "$actual" != "$expected" ]; then
    echo "the $label merged manifest's permissions differ from $allow" >&2
    diff <(printf '%s\n' "$expected") <(printf '%s\n' "$actual") >&2 || true
    exit 1
  fi
  echo "$label merged manifest requests exactly: $(echo $actual)"
}

step "Merged app manifest (bleTransport off, the default) requests exactly the untagged allow-list (libraries can merge some in)"
check_manifest app/build/intermediates/merged_manifests/debug/processDebugManifest/AndroidManifest.xml "$base" "default debug"

step "bleTransport on (-Pwpgd.bleTransport=true, its own build directory app/build-ble): builds, lints, and adds exactly the tagged permissions"
./gradlew -Pwpgd.bleTransport=true :app:assembleDebug :app:lintDebug
ble_merged=app/build-ble/intermediates/merged_manifests/debug/processDebugManifest/AndroidManifest.xml
check_manifest "$ble_merged" "$(printf '%s\n%s\n' "$base" "$ble" | sort)" "bleTransport debug"
# Scanning must be declared neverForLocation: without it Android treats a BLE scan as location access.
if ! tr '\n' ' ' < "$ble_merged" | grep -o -E '<uses-permission[^>]*>' | grep 'android.permission.BLUETOOTH_SCAN' | grep -q 'android:usesPermissionFlags="neverForLocation"'; then
  echo "BLUETOOTH_SCAN is not declared neverForLocation in $ble_merged" >&2
  exit 1
fi
echo "BLUETOOTH_SCAN is neverForLocation"

printf '\nAll local checks passed.\n'
