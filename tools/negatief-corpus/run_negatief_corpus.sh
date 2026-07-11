#!/usr/bin/env bash
# Verify the grammar against the NEGATIVE corpus: malformed RegelSpraak snippets
# that a spec-faithful grammar SHOULD reject.
#
# The positive corpus (tools/verify_corpus.sh) only proves that valid sentences
# parse. It says nothing about whether INVALID sentences are rejected — a grammar
# that over-accepts hands rule analysts silent mis-classifications. This runner is
# the complement: each snippet under tools/negatief-corpus/snippets/ is a mutation
# of a valid form (wrong keyword order, a missing mandatory part, a half date, a
# unit in the wrong place, garbage past EOF, an empty block, ...), and the manifest
# records whether it MUST be rejected (FAIL) or is a documented/known acceptance
# (SURPRISE).
#
# Verdicts (see tools/negatief-corpus/manifest):
#   FAIL     — must be rejected (Parse exit != 0). A snippet that parses anyway is a
#              REGRESSION (the grammar became too loose).
#   SURPRISE — parses on purpose: legal free-name RegelSpraak, a documented
#              divergence, or a known grammar gap (marked [GAT] in the manifest). A
#              snippet that is rejected anyway is a REGRESSION (the grammar became
#              stricter — verify that was intended).
#
# Same start rule as the positive corpus: corpusEntry (EOF-anchored; Parse.java
# also rejects any parse that does not consume the whole token stream).
#
# Usage: tools/negatief-corpus/run_negatief_corpus.sh   (assumes `make compile` ran)
# Exits non-zero if any snippet's actual verdict differs from the manifest.
set -u
cd "$(dirname "$0")/../.."

CP="lib/antlr-4.13.2-complete.jar:out"
DIR="tools/negatief-corpus"
MANIFEST="$DIR/manifest"

if [ ! -e out/Parse.class ]; then
    echo "out/Parse.class missing — run 'make compile' first." >&2
    exit 2
fi

# Parse one snippet under a given start rule; echo "ok" if it parses cleanly,
# else "err". The default rule (corpusEntry) is the corpus harness; the strict
# cross-check drives regelSpraakDocument (the real production entry, no
# harness-only regelAanhef/kaal-fragment).
parse_verdict() {
    local f="$1"; local rule="${2:-corpusEntry}"
    if java -cp "$CP" Parse "$rule" "$f" -no-tree >/dev/null 2>&1; then
        echo ok
    else
        echo err
    fi
}

total=0; fail_ok=0; surprise_ok=0; gaps=0; strict_checked=0; regressions=""
while IFS=$'\t' read -r verwacht bestand klasse strikt toelichting; do
    # Skip comments / blank lines.
    case "${verwacht:-}" in ''|\#*) continue ;; esac
    f="$DIR/snippets/$bestand"
    if [ ! -f "$f" ]; then
        regressions="$regressions\n  MISSING  $bestand (in manifest, not on disk)"
        continue
    fi
    total=$((total+1))
    actual=$(parse_verdict "$f")

    case "$verwacht" in
        FAIL)
            if [ "$actual" = err ]; then
                fail_ok=$((fail_ok+1))
            else
                regressions="$regressions\n  OVER-ACCEPT [$klasse] $bestand :: expected FAIL but PARSED — $toelichting"
            fi
            ;;
        SURPRISE)
            if [ "$actual" = ok ]; then
                surprise_ok=$((surprise_ok+1))
                case "$toelichting" in
                    *'[GAT]'*)
                        gaps=$((gaps+1))
                        printf 'GAT   %-45s %s\n' "$bestand" "$toelichting"
                        ;;
                esac
            else
                regressions="$regressions\n  NOW-REJECTED [$klasse] $bestand :: manifest says SURPRISE but was rejected — $toelichting"
            fi
            ;;
        *)
            regressions="$regressions\n  BAD-VERDICT '$verwacht' for $bestand (use FAIL or SURPRISE)"
            ;;
    esac

    # Strict cross-check (machine-verifies the "STRIKTE regel wijst af" claim so
    # it is shown, not merely asserted). Only rows carrying a strict token.
    case "${strikt:-}" in
        STRICT-FAIL)
            strict_checked=$((strict_checked+1))
            if [ "$(parse_verdict "$f" regelSpraakDocument)" = ok ]; then
                regressions="$regressions\n  STRICT-OVER-ACCEPT [$klasse] $bestand :: manifest says STRICT-FAIL but it PARSED under regelSpraakDocument — $toelichting"
            fi
            ;;
        STRICT-PARSE)
            strict_checked=$((strict_checked+1))
            if [ "$(parse_verdict "$f" regelSpraakDocument)" = err ]; then
                regressions="$regressions\n  STRICT-NOW-REJECTED [$klasse] $bestand :: manifest says STRICT-PARSE but it was rejected under regelSpraakDocument — $toelichting"
            fi
            ;;
    esac
done < "$MANIFEST"

echo "-------------------------------------------------------------------"
echo "negatief corpus: $total snippets — $fail_ok correctly rejected, $surprise_ok parse as expected ($gaps marked [GAT] = grammar too loose); $strict_checked strict cross-checks"
if [ -n "$regressions" ]; then
    echo -e "REGRESSIES:$regressions"
    exit 1
fi
echo "all snippets match their manifest verdict."
