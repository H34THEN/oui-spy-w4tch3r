#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
out=$(mktemp -d)
trap 'rm -rf "$out"' EXIT
c++ -std=c++17 -Wall -Wextra -fsanitize=address,undefined tests/beacon_test.cpp -o "$out/beacon-test"
ASAN_OPTIONS=detect_leaks=0 "$out/beacon-test"
# Assert the bundled firmware has no transmit or connection entry points.
if rg -n 'esp_wifi_(80211_tx|connect|scan_start)|setActiveScan\(true\)|getClient\(|createClient\(' receiver/src; then
  echo 'Forbidden active radio API in passive firmware' >&2
  exit 1
fi
