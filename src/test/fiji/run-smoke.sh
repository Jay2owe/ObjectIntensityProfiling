#!/usr/bin/env bash
# Headless Fiji smoke test for Object Intensity Profiling.
#
#   FIJI_HOME=/path/to/a/disposable/Fiji bash src/test/fiji/run-smoke.sh
#
# Installs the freshly built plugin jar into FIJI_HOME (use a copy of Fiji, not
# your working installation: any older Object_Intensity_Profiling jar there is
# removed), runs src/test/fiji/oip-smoke.ijm headless, then runs a macro with a
# bad option and checks that it fails with a one-line message and no Java stack
# trace. Prints SMOKE OK and exits 0 when every check passes.
#
# Environment:
#   FIJI_HOME   required; the Fiji folder to install into and run.
#   SKIP_BUILD  set to 1 to reuse the jar already in target/.
#   SMOKE_DIR   work folder (default: a new temporary folder).
#   SMOKE_HEAP  Java heap for Fiji (default 2g).
#
# On Windows the Fiji launcher is a GUI program whose console output cannot be
# captured, so this script asks Fiji's Jaunch configurator for the Java command
# it would run and starts the bundled Java directly. Elsewhere the launcher is
# run directly.
set -euo pipefail

if [ -z "${FIJI_HOME:-}" ]; then
    echo "SMOKE FAIL: set FIJI_HOME to a disposable copy of Fiji" >&2
    exit 2
fi
cd "$(dirname "$0")/../../.."
project="$(pwd)"
heap="${SMOKE_HEAP:-2g}"

if [ "${SKIP_BUILD:-0}" != "1" ]; then
    sh ./mvnw -B -q clean verify
fi
version="$(awk '/<artifactId>Object_Intensity_Profiling<\/artifactId>/ { found = 1; next }
    found && /<version>/ { gsub(/.*<version>|<\/version>.*/, ""); print; exit }' pom.xml)"
jar="target/Object_Intensity_Profiling-${version}.jar"
[ -f "$jar" ] || { echo "SMOKE FAIL: $jar not found" >&2; exit 1; }
rm -f "$FIJI_HOME"/plugins/Object_Intensity_Profiling-*.jar
cp "$jar" "$FIJI_HOME/plugins/"

work="${SMOKE_DIR:-$(mktemp -d)}"
mkdir -p "$work"
native() { if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else echo "$1"; fi; }

# Prints the command that starts Fiji headless, one argument per line.
fiji_command() {
    local exe
    exe="$(ls "$FIJI_HOME"/fiji-windows-x64.exe 2>/dev/null || true)"
    local jaunch="$FIJI_HOME/config/jaunch/jaunch-windows-x64.exe"
    if [ -n "$exe" ] && [ -x "$jaunch" ]; then
        local reply java_home
        reply="$(cd "$FIJI_HOME" && printf '2\n%s\n--headless\n' "$(native "$exe")" \
            | "$jaunch" fiji | tr -d '\r')"
        # Reply: JVM, line count, libjvm path, argument count, arguments, main class, main args.
        # The JVM arguments (including a very long class path) go into a Java @argfile.
        local argfile="$work/fiji.args"
        echo "$reply" | awk -v heap="$heap" -v argfile="$argfile" -v nativefile="$(native "$argfile")" '
            function quote(s) { gsub(/\\/, "\\\\", s); gsub(/"/, "\\\"", s); return "\"" s "\"" }
            $0 == "JVM" { jvm = NR }
            jvm && NR == jvm + 2 { libjvm = $0 }
            jvm && NR == jvm + 3 { count = $0 }
            jvm && NR > jvm + 3 && NR <= jvm + 3 + count && $0 !~ /^-Xmx/ { args[++n] = $0 }
            jvm && count && NR == jvm + 4 + count { main = $0 }
            jvm && count && NR == jvm + 5 + count { app = $0 }
            END {
                home = libjvm; sub(/[\\\/]bin[\\\/]server[\\\/]jvm\.dll$/, "", home)
                printf "" > argfile
                for (i = 1; i <= n; i++) print quote(args[i]) >> argfile
                print quote("-Xmx" heap) >> argfile
                print quote("-Djava.awt.headless=true") >> argfile
                close(argfile)
                print home "/bin/java.exe"
                print "@" nativefile
                gsub("/", ".", main); print main
                print app
                print "--headless"
            }'
    else
        ls "$FIJI_HOME"/fiji-* "$FIJI_HOME"/ImageJ-* 2>/dev/null | grep -v '\.\(bat\|sh\)$' | head -n 1
        echo "--headless"
    fi
}

run_macro() {  # run_macro <macro> <argument> <log>
    local command=()
    while IFS= read -r line; do command+=("$line"); done < <(fiji_command)
    local status=0
    timeout 600 "${command[@]}" -macro "$(native "$1")" "$(native "$2")" > "$3" 2>&1 || status=$?
    return $status
}

echo "Smoke folder: $work"
status=0
run_macro "$project/src/test/fiji/oip-smoke.ijm" "$work" "$work/smoke.log" || status=$?
if ! grep -q "SMOKE OK" "$work/smoke.log"; then
    echo "SMOKE FAIL: smoke macro did not finish (exit $status); log follows" >&2
    cat "$work/smoke.log" >&2
    exit 1
fi
grep "^SMOKE" "$work/smoke.log"

bad_status=0
run_macro "$project/src/test/fiji/oip-bad-option.ijm" "$work" "$work/bad-option.log" \
    || bad_status=$?
if ! grep -q "Unknown macro flag: mystery_flag" "$work/bad-option.log"; then
    echo "SMOKE FAIL: bad option was not reported; log follows" >&2
    cat "$work/bad-option.log" >&2
    exit 1
fi
if grep -q "^[[:space:]]*at oip\." "$work/bad-option.log"; then
    echo "SMOKE FAIL: bad option printed a Java stack trace; log follows" >&2
    cat "$work/bad-option.log" >&2
    exit 1
fi
if grep -q "BAD OPTION ACCEPTED" "$work/bad-option.log"; then
    echo "SMOKE FAIL: the macro continued after a bad option" >&2
    exit 1
fi
echo "SMOKE bad option reported on one line (exit $bad_status)"
echo "SMOKE OK"
