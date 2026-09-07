#!/bin/sh
set -eu

ROOT=$(cd "$(dirname "$0")/.." && pwd)
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

cat > "$TMP/java" <<'FAKE'
#!/bin/sh
prev=""
for arg in "$@"; do
    if [ "$prev" = "-classpath" ]; then
        printf 'CLASSPATH=%s\n' "$arg"
        exit 0
    fi
    prev="$arg"
done
printf 'CLASSPATH=MISSING\n'
FAKE
chmod +x "$TMP/java"

out=$(PATH="$TMP:$PATH" JAVA_HOME= sh "$ROOT/gradlew" --version)
cp=$(printf '%s\n' "$out" | sed -n 's/^CLASSPATH=//p')

if [ -z "$cp" ] || [ "$cp" = "MISSING" ]; then
    echo "FAIL: java tidak menerima argumen -classpath"
    exit 1
fi
if [ ! -f "$cp" ]; then
    echo "FAIL: -classpath menunjuk '$cp' yang tidak ada (gradlew tidak menemukan wrapper jar)"
    exit 1
fi
case "$cp" in
    *gradle/wrapper/gradle-wrapper.jar) ;;
    *) echo "FAIL: -classpath bukan gradle-wrapper.jar: $cp"; exit 1 ;;
esac

echo "PASS: gradlew memakai wrapper jar yang benar: $cp"
