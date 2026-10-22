#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
compiler="${KOTLIN_HOME:+$KOTLIN_HOME/bin/}kotlinc"
if ! command -v "$compiler" >/dev/null 2>&1; then
    echo 'Set KOTLIN_HOME to a Kotlin compiler distribution or add kotlinc to PATH.' >&2
    exit 1
fi
output="$(mktemp -d)"
trap 'rm -rf "$output"' EXIT
"$compiler" \
    "$root/app/src/main/kotlin/com/spizganed/quickbuds/bluetooth/RfcommConnector.kt" \
    "$root/app/src/main/kotlin/com/spizganed/quickbuds/bluetooth/ConfirmedToggle.kt" \
    "$root/app/src/main/kotlin/com/spizganed/quickbuds/protocol/OppoPacketFramer.kt" \
    "$root/app/src/main/kotlin/com/spizganed/quickbuds/protocol/BlePacketSizing.kt" \
    "$root/tests/RfcommConnectorTest.kt" \
    "$root/tests/ConfirmedToggleTest.kt" \
    -include-runtime -d "$output/rfcomm-tests.jar"
java -jar "$output/rfcomm-tests.jar"
