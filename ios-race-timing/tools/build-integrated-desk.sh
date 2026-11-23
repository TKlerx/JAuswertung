#!/usr/bin/env bash
set -euo pipefail

repo=$(cd "$(dirname "$0")/../.." && pwd)
work="$repo/ios-race-timing/build/integration"
mkdir -p "$work"
work=$(cd "$work" && pwd)
desk="$work/desk"
maven=(mvn -B -ntp -DskipTests "-Dmaven.repo.local=$work/maven")

clone_at() {
    local url=$1 revision=$2 destination=$3
    git -c advice.detachedHead=false init -q "$destination"
    git -C "$destination" fetch -q --depth=1 "$url" "$revision"
    git -C "$destination" checkout -q --detach FETCH_HEAD
}

while read -r repo_name revision modules; do
    name=${repo_name##*/}
    directory="$work/deps/$name"
    clone_at "https://github.com/$repo_name.git" "$revision" "$directory"
    if [[ -n "$modules" ]]; then
        "${maven[@]}" -Dlombok.version=1.18.48 -f "$directory/pom.xml" -pl "$modules" -am install
    else
        "${maven[@]}" -f "$directory/pom.xml" install
    fi
done <<'DEPS'
dennisfabri/uistate 644253f763a0aff9e824cce7a4a36e27cfcc6759
dennisfabri/swingfx a320fa094580812bc5af23f27b530362fc657700
dennisfabri/commons.xmt 739fb04177eb7ef4c3f9aa9de7ef5be3c902dc4f
dennisfabri/l2fprod-common 4d92b7d78f6fd2a9d93fe6eb6e5749fa73363866
dennisfabri/filechooser 34e09784caa43c48c21a4f9f43a7a5b0cf16f82b
dennisfabri/jutils-legacy 73c94fe9bc6b8792c5dc3aedc962b044842c986c
LISASP-org/java-basics ae78a6e7266cf8204a2aec4d8cc9de9398875acd basics-jre
dennisfabri/AlphaServer fed5bd9fb0e388aca1236bb3dfd9f10ab220dd96
LISASP-org/alphatimer 5bd486decb594b4abcd4f16c3271cc8c9b595011 alphatimer-legacy-api
DEPS

clone_at https://github.com/TKlerx/JAuswertung.git e61337b2252913738ab98e05262c485f99db9500 "$desk"
python3 - "$desk" <<'PY'
from pathlib import Path
import re
import sys

desk = Path(sys.argv[1])
for relative in ("pom.xml", "jauswertung-swing/pom.xml"):
    path = desk / relative
    source = path.read_text()
    dependency = re.search(r"<dependency>[^<]*(?:(?!</dependency>).)*?<artifactId>filechooser-platform</artifactId>.*?</dependency>", source, re.S)
    assert dependency, relative
    entry = dependency.group()
    source = source[:dependency.start()] + entry.replace("filechooser-platform", "filechooser-jfx") + "\n" + entry.replace("filechooser-platform", "filechooser-l2f") + source[dependency.end():]
    path.write_text(source)
init = desk / "jauswertung-swing/src/main/java/de/df/jauswertung/util/DefaultInit.java"
init.write_text(init.read_text()
    .replace("import org.lisasp.swing.filechooser.platform.PlatformFileChooser;",
             "import org.lisasp.swing.filechooser.FileChooserUtils;\nimport org.lisasp.swing.filechooser.l2f.FileChooserL2f;")
    .replace("PlatformFileChooser.initialize();", "FileChooserUtils.initialize(new FileChooserL2f());"))
PY
"${maven[@]}" -f "$desk/pom.xml" -pl jauswertung -am package dependency:build-classpath \
    -DincludeScope=runtime "-Dmdep.outputFile=$work/classpath.txt"
classpath="$desk/jauswertung/target/classes:$(cat "$work/classpath.txt")"
javac -encoding UTF-8 -cp "$classpath" -d "$work" "$repo/ios-race-timing/tools/IntegratedMixedPilot.java"
