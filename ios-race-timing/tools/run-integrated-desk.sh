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
python3 "$repo/ios-race-timing/tools/ios_desk_control.py" "$work" &
desk_control=$!
trap 'kill "$desk_control" 2>/dev/null || true' EXIT

for attempt in {1..90}; do
    if [[ -f "$work/control-ready" ]]; then break; fi
    kill -0 "$desk_control"
    sleep 1
done
test -f "$work/control-ready"

cd "$repo/ios-race-timing"
xcodebuild test -project Zeitnahme.xcodeproj -scheme Zeitnahme \
    -destination "platform=iOS Simulator,id=$device" -derivedDataPath build \
    -resultBundlePath build/IntegratedTiming.xcresult -parallel-testing-enabled NO \
    -only-testing:ZeitnahmeUITests/IntegratedHeatTests \
    CODE_SIGNING_ALLOWED=YES CODE_SIGN_IDENTITY="-" CODE_SIGNING_REQUIRED=NO
