#!/usr/bin/env bash
set -euo pipefail

repo=$(cd "$(dirname "$0")/../.." && pwd)
work="$repo/ios-race-timing/build/integration"
desk="$work/desk"
device=$1
cd "$work"

openssl req -new -x509 -nodes -newkey rsa:2048 -days 2 -sha256 \
    -subj '/CN=JAuswertung iOS Test CA' -addext 'basicConstraints=critical,CA:TRUE' \
    -addext 'keyUsage=critical,keyCertSign,cRLSign' -addext 'subjectKeyIdentifier=hash' \
    -keyout ca.key -out ca.pem
openssl req -new -nodes -newkey rsa:2048 -subj '/CN=localhost' -keyout server.key -out server.csr
cat > server.ext <<'EXTENSIONS'
basicConstraints=critical,CA:FALSE
keyUsage=critical,digitalSignature,keyEncipherment
extendedKeyUsage=serverAuth
subjectAltName=DNS:localhost,IP:127.0.0.1
authorityKeyIdentifier=keyid:always
EXTENSIONS
openssl x509 -req -in server.csr -CA ca.pem -CAkey ca.key -CAcreateserial -days 1 -sha256 \
    -extfile server.ext -out server.pem
openssl pkcs12 -export -inkey server.key -in server.pem -certfile ca.pem -out desktop.p12 \
    -passout pass:pilot-test-password
if ! xcrun simctl list devices booted | grep -Fq "$device"; then xcrun simctl boot "$device"; fi
xcrun simctl bootstatus "$device" -b
xcrun simctl keychain "$device" add-root-cert "$work/ca.pem"

export PILOT_JAVA_HOME="$JAVA_HOME"
gui="gui/$(id -u)"
label="de.df.jauswertung.timing-pilot"
controller="$repo/ios-race-timing/tools/ios_desk_control.py"
if launchctl print "$gui" >/dev/null 2>&1; then
    python3 - "$label" "$controller" "$work" <<'PY'
import os
from pathlib import Path
import plistlib
import sys

label, controller, directory = sys.argv[1:]
work = Path(directory)
with (work / "desk-control.plist").open("wb") as target:
    plistlib.dump({"Label": label, "RunAtLoad": True, "LimitLoadToSessionType": "Aqua",
                   "ProgramArguments": [sys.executable, controller, directory],
                   "EnvironmentVariables": {"PILOT_JAVA_HOME": os.environ["PILOT_JAVA_HOME"]},
                   "StandardErrorPath": str(work / "desk-control.log")}, target)
PY
fi
if [[ -f desk-control.plist ]] && launchctl bootstrap "$gui" "$work/desk-control.plist"; then
    trap 'launchctl bootout "$gui/$label" 2>/dev/null || true' EXIT
else
    AWT_FORCE_HEADFUL=true python3 "$controller" "$work" 2> desk-control.log &
    desk_control=$!
    trap 'kill "$desk_control" 2>/dev/null || true' EXIT
fi

for attempt in {1..180}; do
    if [[ -f "$work/control-ready" ]]; then break; fi
    if [[ -s "$work/desk-control.log" ]]; then break; fi
    sleep 1
done
if [[ ! -f "$work/control-ready" ]]; then
    for log in desk-control.log process-result.txt; do
        if [[ -f "$log" ]]; then cat "$log"; fi
    done
    exit 1
fi

cd "$repo/ios-race-timing"
xcodebuild test -project Zeitnahme.xcodeproj -scheme Zeitnahme \
    -destination "platform=iOS Simulator,id=$device" -derivedDataPath build \
    -resultBundlePath build/IntegratedTiming.xcresult -parallel-testing-enabled NO \
    -only-testing:ZeitnahmeUITests/IntegratedHeatTests \
    CODE_SIGNING_ALLOWED=YES CODE_SIGN_IDENTITY="-" CODE_SIGNING_REQUIRED=NO
