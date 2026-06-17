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
rm -f examples/spec-main/*.rs examples/spec-antlr/*.rs
python3 tools/extract_corpus.py "$SPEC_DIR/RegelSpraak-specificatie v2.3.0.md"        examples/spec-main  spec-main  2>/dev/null >/dev/null
python3 tools/extract_corpus.py "$SPEC_DIR/RegelSpraak-spec-for-ANTLR-v2.3.0.md"      examples/spec-antlr spec-antlr 2>/dev/null >/dev/null

# A snippet that is a known, documented source/conversion defect (see
# docs/grammar-divergences.md) — not counted as a grammar failure.
is_known_defect() {
    case "$1" in
        # PDF→Markdown conversion dropped the quotes around the Luchthavens
        # enumeration values (quoted everywhere else in the spec), and glued an
        # orphaned objecttype member block onto two Domein definitions. With
        # newlines non-structural, unquoted multi-word enum values are unparseable.
        spec-main-*-de-datum-tijd-voor-het-berekenen.rs) return 0 ;;
    esac
    return 1
}

pass=0; fail=0; defect=0; failures=""
for f in examples/toka/*.rs examples/spec-antlr/*.rs examples/spec-main/*.rs; do
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
