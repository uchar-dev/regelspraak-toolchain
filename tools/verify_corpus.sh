#!/usr/bin/env bash
# Verify the grammar against the full RegelSpraak v2.3.0 example corpus.
#
# The corpus is every ```regelspraak fenced block in the TOKA casus and the two
# specification markdown files, MINUS the Chapter-13 EBNF productions (which are
# grammar *notation*, not RegelSpraak programs — see tools/extract_corpus.py).
#
# The spec's worked examples are mostly FRAGMENTS of a single non-terminal, not
# whole documents, so each is parsed under the `corpusEntry` rule: a union of the
# documented top-level fragment kinds, anchored on EOF (Parse.java additionally
# rejects any parse that does not consume the whole token stream). A snippet
# "passes" iff it parses with no lexer/parser error, no error node, and all input
# consumed.
#
# Usage: tools/verify_corpus.sh        (assumes `make compile` already ran)
# Exits non-zero if any snippet outside the documented exceptions fails.
set -u
cd "$(dirname "$0")/.."

SPEC_DIR="specification/v2.3.0"
CP="lib/antlr-4.13.2-complete.jar:out"

# (Re)extract the corpus from the spec — keeps fixtures in lock-step with the spec.
# The normative spec (RegelSpraak-specificatie v2.3.0.md) and TOKA casus are NOT
# redistributed here (© Belastingdienst, external — see $SPEC_DIR/README.md).
# examples/spec-main extracts from it, so that corpus only exists when the spec is
# present locally; examples/spec-antlr comes from the in-repo ANTLR mapping.
rm -f examples/spec-main/*.rs examples/spec-antlr/*.rs examples/spec-main/*.rgs examples/spec-antlr/*.rgs
if [ -f "$SPEC_DIR/RegelSpraak-specificatie v2.3.0.md" ]; then
  python3 tools/extract_corpus.py "$SPEC_DIR/RegelSpraak-specificatie v2.3.0.md"        examples/spec-main  spec-main  2>/dev/null >/dev/null
  # Spec present but nothing extracted = a broken extractor, not an empty corpus: say so instead of
  # quietly running a 47-snippet gate that reads as green.
  shopt -s nullglob; hoofd=(examples/spec-main/*.rgs); shopt -u nullglob
  [ ${#hoofd[@]} -gt 0 ] || { echo "verify_corpus: spec aanwezig maar extract_corpus.py leverde geen snippets in examples/spec-main"; exit 2; }
else
  echo "verify_corpus: normative spec absent (external, © Belastingdienst) — skipping examples/spec-main corpus."
fi
python3 tools/extract_corpus.py "$SPEC_DIR/RegelSpraak-spec-for-ANTLR-v2.3.0.md"      examples/spec-antlr spec-antlr 2>/dev/null >/dev/null

# A snippet that is a known, documented source/conversion defect (see
# docs/grammar-divergences.md) — not counted as a grammar failure.
is_known_defect() {
    case "$1" in
        # PDF→Markdown conversion dropped the quotes around the Luchthavens
        # enumeration values (quoted everywhere else in the spec), and glued an
        # orphaned objecttype member block onto two Domein definitions. With
        # newlines non-structural, unquoted multi-word enum values are unparseable.
        spec-main-*-de-datum-tijd-voor-het-berekenen.rgs) return 0 ;;
    esac
    return 1
}

pass=0; fail=0; defect=0; failures=""
# nullglob, because examples/spec-main/ only exists when the external spec is present: without it bash
# leaves the pattern itself in the word list and Parse "fails" on a file that was never there — a phantom
# UNEXPECTED FAILURE with an empty diagnosis. The emptiness checks below keep that from masking a real
# empty corpus.
shopt -s nullglob
corpus=(examples/toka/*.rgs examples/spec-antlr/*.rgs examples/spec-main/*.rgs)
shopt -u nullglob
[ ${#corpus[@]} -gt 0 ] || { echo "verify_corpus: leeg corpus — geen enkel .rgs-fragment gevonden"; exit 2; }
for f in "${corpus[@]}"; do
    base=$(basename "$f")
    err=$(java -cp "$CP" Parse corpusEntry "$f" -no-tree 2>&1 >/dev/null)
    if [ -z "$err" ]; then
        pass=$((pass+1))
    elif is_known_defect "$base"; then
        defect=$((defect+1))
        printf 'DEFECT %-55s %s\n' "$base" "$(echo "$err" | grep -m1 -E 'line ')"
    else
        fail=$((fail+1))
        failures="$failures\n  $base :: $(echo "$err" | grep -m1 -E 'line ')"
    fi
done

total=$((pass+fail+defect))
echo "-------------------------------------------------------------------"
echo "corpus: $pass passed, $fail failed, $defect known-defect  (of $total)"
if [ -n "$failures" ]; then
    echo -e "UNEXPECTED FAILURES:$failures"
    exit 1
fi
echo "all snippets parse (known documented defects excepted)."
