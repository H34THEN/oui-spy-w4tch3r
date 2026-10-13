#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
out=$(mktemp -d)
trap 'rm -rf "$out"' EXIT
javac -d "$out" app/src/main/java/com/heathen/persephone/RfCore.java app/src/main/java/com/heathen/persephone/DetectorTags.java app/src/main/java/com/heathen/persephone/SignalMapModel.java app/src/main/java/com/heathen/persephone/OverlayProjection.java tests/CoreTest.java
java -cp "$out" com.heathen.persephone.CoreTest
