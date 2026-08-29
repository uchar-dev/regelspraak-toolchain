# Grammar divergences — RegelSpraak frontend

Tracked divergences of this frontend's grammar from the RegelSpraak **v2.3.0**
specification (the arbiter). Each divergence is either a reconstruction of a
defective source production (noted at the rule in the `.g4` files) or an
**additive dialect extension** that is *inert on official v2.3.0 documents*.
The lexer/parser headers carry the per-rule rationale; this file records the
extensions and their lowering at one place.

The frontend takes the same inert-extension posture the grammar already uses
for `Regelgroep` (§9.10 is prose-only in v2.3.0) and the scenariotoetsen test
layer: the extension's opening tokens occur **nowhere** in the flat v2.3.0
corpus, so an official document parses byte-for-byte as before and emits an IR
with no extension keys.

---

## §4 The ladder extension — `Uitzondering op` / `Uitzonderingen ongeordend`

Origin: `spec/proposals/LADDER-EVOLUTIE.md` §4 (ratified 2026-08-29, Q1a). The
uchar IR carries a `DefaultLadder` — a prioritised default calculus (one writer,
one target, rungs in descending priority; `spec/RUNTIME-SEMANTICS.md` ch. 7).
Official RegelSpraak v2.3.0 has **no** exception, priority, default-override or
rule-ordering construct, so a flat RegelSpraak document can never denote a
ladder. This dialect extension adds a source channel for authoring one.

**Recognition only ever advises; it never folds.** A flat rule family is NEVER
transformed into a ladder — the priority a ladder encodes is not recoverable
from a source that never wrote it (LADDER-EVOLUTIE §4.2). Only the extension's
own blocks lower to ladders.

### Surface syntax (additive; new top-level alternatives)

Two forms sit beside `regel` / `regelGroep` / `beslistabel` in
`regelSpraakDocument`:

**Ordered exception** — stacks an exception above a named base rule:

```
Regel <basisregelnaam>
    geldig <versie>
        <resultaat>            (kenmerktoekenning of gelijkstelling)
        [indien <voorwaarde>]

Uitzondering op <basisregelnaam>: <uitzonderingsnaam>
    geldig <versie>
        <resultaat>            (zelfde doel als de basisregel)
        [indien <voorwaarde>]
```

New tokens: `UITZONDERING_OP` (`'Uitzondering op'`, a whole-phrase token). The
base name runs up to the `:`; the exception's own name is an ordinary
`regelNaam` (up to `geldig`), so the body's `regelVersie+` is reached exactly as
a §13.4.2 rule's is.

**Unordered group** — the only §9.5-conflict surface:

```
Uitzonderingen ongeordend:
Regel <lidnaam-1>
    geldig <versie>
        <resultaat>            (één gedeeld doel)
        [indien <voorwaarde>]
Regel <lidnaam-2>
    ...
```

New tokens: `UITZONDERINGEN` (`'Uitzonderingen'`), `ONGEORDEND`
(`'ongeordend'`). Like `Regelgroep`, the group scopes the **consecutive**
`regel` constructs that follow it and ends at the first non-rule construct, so
it is inert on any non-extension document. `UITZONDERINGEN` is never a prefix of
`UITZONDERING_OP` (`'Uitzonderingen'` has no space after `Uitzondering`), so the
two whole-phrase/keyword tokens never collide under maximal munch.

### Lowering (resolver)

Both forms LIFT already-resolved rules out of `resolvedRegels` into
`defaultLadders`: WF-050 forbids both a rule and a ladder on one target, so the
lifted rule's semantics live in the ladder alone. Each lifted rule keeps a
`Regel` stub in `regels` (mirroring the hand-written ladder anchors).

- **Ordered:** the base rule and its `Uitzondering op` blocks become ONE
  `DefaultLadder`. Rungs descend in priority — the exception rung(s) above, the
  base rung below. With multiple exceptions on one base, the **later-written**
  block stacks **higher** (LADDER-EVOLUTIE §4.2). A characteristic base yields a
  `besluit`-carrying rung (a RegelSpraak kenmerk assignment is monotone `waar`,
  §9.2 — the frontend never emits `besluit: false`); an attribute base yields a
  `consequentie`-carrying rung. The base rule's geldigheid is carried onto the
  ladder.
- **Unordered:** each `Uitzonderingen ongeordend` block becomes SIBLING
  declared-unordered ladders (one per member) over one shared target — the
  `toelagegronden`-anchor shape. Every sibling carries `ongeordend: true`
  (WF-050: two-or-more ladders on one target require the marking on every one).
  Members deciding differently on one target route to the §9.5 Inconsistentie
  channel; deciding the same reconciles idempotently. Because a kenmerk
  assignment is monotone `waar`, a genuine §9.5 conflict is only expressible on
  an **attribute** target.

### Fail-fast refusals (resolver, loud diagnostics)

- an `Uitzondering op` naming a base rule that does not exist;
- an exception (or group member) writing a DIFFERENT target than the base
  (or than a prior member) — a ladder is over ONE `(onderwerp, kenmerk XOR
  attribuut)`;
- a base rule that is not a plain kenmerktoekenning/gelijkstelling (an
  initialisatie, or a verdeling/consistentie/objectcreatie/uniciteit);
- a base rule or exception with more than one `geldig`-versie (not carried by
  this first card).

### Emission (v0-mirror invariant)

`defaultLadders` is emitted ONLY when the extension produced ladders. A document
without the extension emits no such key, so the v0-mirror byte-identity of every
flat golden holds. Within the list, `attribuut` is emitted only on an attribute
ladder and `ongeordend` only when true, matching the schema and the hand-written
ladder anchors.

### Scope / limitations of the first card

- Only the kenmerk form is exercised for the ordered shape (an attribute-target
  ordered exception is expressible by the same machinery but not needed for
  the first goldens).
- Exception-on-exception (nested `Rung.excepties`) is not authored from source
  in this card; the wire form supports it and the hand anchors witness it.
- Multi-version base/exception rules are refused (single-version only).
- The new keyword tokens narrow the ordinary-name space: `Uitzonderingen`
  (capitalised) and `ongeordend` are reserved and can no longer appear as
  name words (e.g. a plural `(mv: Uitzonderingen)` or a kenmerk named
  `ongeordend` now refuses loudly at the lexer) — the same loud posture the
  existing `Regelgroep`/`recursief` extension tokens take. The corpus and
  every committed source are free of these words.
- An exception whose validity window differs from its base refuses loudly:
  the ladder carries one window and a per-rung window is unrepresentable —
  split per window or align the windows.
