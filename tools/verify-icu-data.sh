#!/bin/sh
# Verify that the ICU data app/build.gradle.kts drops is data UTS #46 never
# reads.
#
# ICU4J is a dependency for one thing — the hostname normalization in
# data/Matching.kt — and ships ~38 MiB of data for everything else it can do.
# The build excludes the parts IDNA does not consult. Those are Java resources,
# so nothing in the build can prove the exclusion is safe; this script does.
#
# It converts every assigned Unicode code point through UTS #46 twice, once
# against the full data and once against only the files the build keeps, and
# fails if a single result or error differs.
#
# Run it after changing the icu4j version or the exclusion list.
#
#     tools/verify-icu-data.sh [icu4j-version]
#
# Needs javac, java and curl, and downloads the icu4j jar into a temp dir.
set -eu

VERSION="${1:-76.1}"
MIRROR="${ICU_MAVEN_BASE:-https://repo1.maven.org/maven2}"
JAR_URL="$MIRROR/com/ibm/icu/icu4j/$VERSION/icu4j-$VERSION.jar"

# The files the build keeps. Anything else under icudata/ must be unreachable
# from UTS #46; keep this in step with the excludes in app/build.gradle.kts.
KEEP="nfc.nrm nfkc.nrm pnames.icu ubidi.icu ucase.icu uprops.icu uts46.nrm"

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
cd "$work"

echo "Fetching icu4j $VERSION..."
curl -fsSL -o icu4j.jar "$JAR_URL"

mkdir full
(cd full && unzip -q ../icu4j.jar)
cp -r full trimmed

data="trimmed/com/ibm/icu/impl/data/icudata"
find "$data" -mindepth 1 -maxdepth 1 -type d -exec rm -rf {} +
keep_args=""
for f in $KEEP; do
    keep_args="$keep_args ! -name $f"
done
# shellcheck disable=SC2086 # keep_args is a deliberately unquoted argument list
find "$data" -maxdepth 1 -type f $keep_args -delete

echo "Full data:    $(du -sh full/com/ibm/icu/impl/data/icudata | cut -f1)"
echo "Trimmed data: $(du -sh "$data" | cut -f1)"

cat > Sweep.java <<'JAVA'
import com.ibm.icu.text.IDNA;

/** Converts every assigned code point through UTS #46 and prints the result. */
public class Sweep {
    public static void main(String[] args) {
        IDNA uts46 = IDNA.getUTS46Instance(
            IDNA.NONTRANSITIONAL_TO_ASCII | IDNA.CHECK_BIDI | IDNA.CHECK_CONTEXTJ);
        StringBuilder all = new StringBuilder();
        for (int cp = 0x20; cp <= 0x10FFFF; cp++) {
            if (Character.getType(cp) == Character.UNASSIGNED) continue;
            String host = new String(Character.toChars(cp)) + "x.example";
            StringBuilder out = new StringBuilder();
            IDNA.Info info = new IDNA.Info();
            try {
                uts46.nameToASCII(host, out, info);
            } catch (RuntimeException e) {
                all.append(Integer.toHexString(cp)).append("\tEXCEPTION\t").append(e).append('\n');
                continue;
            }
            all.append(Integer.toHexString(cp)).append('\t').append(out).append('\t')
               .append(info.hasErrors() ? info.getErrors() : "-").append('\n');
        }
        System.out.print(all);
    }
}
JAVA

javac -cp full Sweep.java -d .
java -cp "full:." Sweep > full.out
java -cp "trimmed:." Sweep > trimmed.out

if cmp -s full.out trimmed.out; then
    echo "OK: $(wc -l < full.out) code points, identical with and without the excluded data."
else
    echo "FAIL: UTS #46 behaves differently without the excluded data:" >&2
    diff full.out trimmed.out | head -20 >&2
    exit 1
fi
