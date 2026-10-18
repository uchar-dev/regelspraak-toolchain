#!/usr/bin/env python3
"""csv_to_beslistabel.py — convert a CSV decision table into the canonical §12
RegelSpraak `Beslistabel` markdown-pipe-table surface (an .rgs fragment).

Sibling of tools/extract_corpus.py: pure stdlib, python3, no deps. This is an
IMPORT PATH, not a second grammar — analysts draft in Excel/CSV and the tool
shapes the exact markdown pipe-table the parser already accepts. The tool does
NOT validate RegelSpraak content; the resolver stays the sole authority on
meaning. The tool only forms the table.

CSV CONTRACT
------------
Optional metadata rows, recognised BEFORE the header row, each with EXACTLY two
fields and a case-sensitive first cell:
    naam;<beslistabelnaam>       -> the `Beslistabel <naam>` header
    geldig;<geldigheid>          -> the geldigheid line (default: "geldig altijd")

Row 1 (the first non-metadata row) = the column-header SENTENCES exactly as the
markdown form expects them: the doelkolom sentence first, then the "indien …"
condition columns. There is NO leading empty/row-number column in the CSV — the
tool adds that column and emits the running row numbers (1..N) itself.

Data rows = the numbered rows. Each CSV data row carries ONLY the value cell plus
the condition cells (no number). The tool emits the running row number.

CELL RULES
----------
 - Cells pass through VERBATIM into the markdown cells, EXCEPT `|` which is
   invalid in the markdown pipe-table form -> HARD ERROR naming row+column
   (never silent mangling).
 - UNQUOTED cells are .strip()'d (edge whitespace removed) so the markdown stays
   tidy; therefore leading/trailing spaces in an unquoted cell are NOT significant.
   RFC-4180-QUOTED cells keep their content verbatim — quoted leading/trailing
   spaces ARE preserved (that is how you make edge whitespace meaningful).
 - Separator is `;` or `,`, chosen structurally with `;` preferred (RegelSpraak
   enum cells legitimately contain commas, so `;` is the safe default; `,` is
   picked only when `;` fails to split the header). Falls back to `;`.
 - Every data row must have EXACTLY as many cells as the header row; otherwise a
   HARD ERROR naming the row and the expected/got column counts.

QUOTING / WHITESPACE NOTE
-------------------------
RFC-4180 quoting is honoured for FIELD SPLITTING: a quoted cell may contain the
separator, embedded double-quotes (as `""`), and leading/trailing spaces without
breaking parsing. The tool preserves the *content* of a quoted cell verbatim into
the markdown cell — including its edge whitespace. An UNQUOTED cell's edge
whitespace is cosmetic padding and is trimmed. So `"a; b"` (sep `;`) stays
`a; b`; `" x "` stays ` x ` (spaces kept); a bare ` x ` becomes `x`. Interior
spaces always survive. A cell whose content itself contains double-quote
characters (RegelSpraak string literals) is written by wrapping the whole cell in
quotes and doubling each inner quote — exactly what a spreadsheet CSV export
emits.

USAGE
-----
    csv_to_beslistabel.py [--out <pad>] [<csv-in>]
      <csv-in>   input CSV file; omit (or "-") to read stdin
      --out      output .rgs path; omit to write stdout

Exit 0 on success, 1 on error. On error the message (with row+column where
applicable) goes to stderr and NOTHING is written to the output stream.
"""
import argparse
import csv
import sys


class ConvertError(Exception):
    """A user-facing conversion error; its message already names row/column."""


def _colref(idx):
    """1-based human column label. Column 1 is the value (doelkolom) cell;
    columns 2.. are the condition ("indien …") cells."""
    return idx + 1


def _dialect(delim):
    d = csv.excel()
    d.delimiter = delim
    return d


def _choose_delimiter(text):
    """Pick `;` vs `,`, preferring `;` (the contract fallback).

    RegelSpraak enum cells legitimately contain commas ("A", "B" of "C"), so a
    naive csv.Sniffer misfires on `,`. The decision is STRUCTURAL: parse the
    non-empty rows under each candidate and prefer the delimiter that yields a
    consistent, multi-column table. `;` wins ties — it is the documented default
    and the metadata rows (`naam;…`, `geldig;…`) are `;`-shaped. `,` is chosen
    only when `;` degenerates to a single column while `,` gives more.
    """
    lines = [ln for ln in text.splitlines() if ln.strip()]
    if not lines:
        return _dialect(";")

    def header_cols(delim):
        # Column count of the first NON-metadata row under this delimiter.
        rows = list(csv.reader(lines, _dialect(delim)))
        for r in rows:
            if not (len(r) == 2 and r[0] in ("naam", "geldig")):
                return len(r)
        return len(rows[-1]) if rows else 1

    semi = header_cols(";")
    comma = header_cols(",")
    # Prefer ';' unless it fails to split the header (1 col) and ',' does better.
    if semi <= 1 and comma > semi:
        return _dialect(",")
    return _dialect(";")


def _parse_rows(text, delim):
    """RFC-4180 field parser that records, per field, whether it was QUOTED.

    The stdlib csv module discards this bit, but we need it: unquoted edge
    whitespace is cosmetic padding and is trimmed, whereas RFC-4180-QUOTED edge
    whitespace is meaningful content and is preserved verbatim (V3). Returns a
    list of rows, each a list of (value, was_quoted) tuples. Handles the `""`
    in-field escape and quoted newlines.
    """
    rows = []
    field = []
    row = []
    quoted = False          # this field contains a quoted run
    in_quotes = False
    i = 0
    n = len(text)
    started_field = False   # a field exists on this line (even if empty)

    def end_field():
        nonlocal field, quoted, started_field
        row.append(("".join(field), quoted))
        field = []
        quoted = False
        started_field = False

    while i < n:
        c = text[i]
        if in_quotes:
            if c == '"':
                if i + 1 < n and text[i + 1] == '"':
                    field.append('"')
                    i += 2
                    continue
                in_quotes = False
                i += 1
                continue
            field.append(c)
            i += 1
            continue
        # not in quotes
        if c == '"':
            in_quotes = True
            quoted = True
            started_field = True
            i += 1
            continue
        if c == delim:
            end_field()
            i += 1
            started_field = True  # a delimiter implies another field follows
            continue
        if c == '\r':
            i += 1
            continue
        if c == '\n':
            end_field()
            rows.append(row[:])
            row.clear()
            i += 1
            continue
        field.append(c)
        started_field = True
        i += 1

    # Flush a final field/row with no trailing newline.
    if started_field or field or quoted or row:
        end_field()
        rows.append(row[:])
    return rows


def _check_cell(pair, row_label, col_idx):
    """`pair` is (value, was_quoted). VERBATIM apart from `|`, which is invalid in
    the markdown form -> hard error naming row+column. Unquoted cells are trimmed
    of edge whitespace (cosmetic padding); QUOTED cells keep their content verbatim
    (RFC-4180 leading/trailing spaces are meaningful — V3)."""
    value, was_quoted = pair
    if "|" in value:
        raise ConvertError(
            f"rij {row_label}, kolom {_colref(col_idx)}: cel bevat een '|' "
            f"(ongeldig in de markdown-beslistabel-vorm): {value!r}"
        )
    return value if was_quoted else value.strip()


def convert(text):
    """CSV text -> the `Beslistabel` .rgs fragment (str). Raises ConvertError."""
    dialect = _choose_delimiter(text)
    delim = dialect.delimiter
    rows = _parse_rows(text, delim)

    # Drop wholly-empty rows (a trailing blank line, an Excel artefact row).
    rows = [r for r in rows if any(v.strip() for v, _ in r)]

    name = None
    validity = None
    idx = 0
    # Metadata rows: EXACTLY 2 fields, first cell exactly "naam"/"geldig"
    # (case-sensitive). Metadata values are trimmed regardless of quoting.
    while idx < len(rows):
        r = rows[idx]
        if len(r) == 2 and r[0][0] == "naam":
            name = r[1][0].strip()
            idx += 1
        elif len(r) == 2 and r[0][0] == "geldig":
            validity = r[1][0].strip()
            idx += 1
        else:
            break

    if name is None:
        name = "Beslistabel"  # placeholder; the resolver names the authority
    if validity is None:
        validity = "geldig altijd"

    remaining = rows[idx:]
    if not remaining:
        raise ConvertError(
            "geen header-rij gevonden: de CSV bevat na eventuele metadata geen "
            "kolom-header-rij (rij 1 = de doelkolom-zin + 'indien …'-kolommen)"
        )

    header = remaining[0]
    ncols = len(header)
    if ncols < 1 or all(not v.strip() for v, _ in header):
        raise ConvertError("de header-rij (rij 1) is leeg")

    # "rij 1" in messages = the header. Data rows are numbered from 1 in the
    # EMITTED table but reported by their CSV position for diagnostics.
    header_cells = [_check_cell(c, 1, i) for i, c in enumerate(header)]

    data_rows = remaining[1:]
    if not data_rows:
        raise ConvertError(
            "geen datarijen gevonden: na de header-rij staan geen genummerde rijen"
        )

    out_data = []
    for di, r in enumerate(data_rows):
        # CSV line for diagnostics: metadata + header + this data row's offset.
        row_label = idx + 1 + 1 + di  # 1-based CSV logical row (post empty-drop)
        if len(r) != ncols:
            raise ConvertError(
                f"rij {row_label}: verkeerd kolomaantal — verwacht {ncols}, "
                f"kreeg {len(r)}"
            )
        cells = [_check_cell(c, row_label, i) for i, c in enumerate(r)]
        out_data.append(cells)

    return _render(name, validity, header_cells, out_data)


def _render(name, validity, header_cells, data_rows):
    """Assemble the markdown pipe-table. Row-number column is prepended: header
    cell empty (`|   |`), data cells the running number (1..N). Separator line
    has one `---` per column INCLUDING the row-number column."""
    lines = []
    lines.append(f"Beslistabel {name}")
    lines.append(f"    {validity}")

    # Header: leading empty row-number cell, then the header sentences.
    hdr = "    |   | " + " | ".join(header_cells) + " |"
    lines.append(hdr)

    # Separator: one --- per column, incl. the row-number column.
    sep = "    |---|" + "|".join(["---"] * len(header_cells)) + "|"
    lines.append(sep)

    for n, cells in enumerate(data_rows, start=1):
        row = f"    | {n} | " + " | ".join(cells) + " |"
        lines.append(row)

    return "\n".join(lines) + "\n"


def main(argv=None):
    p = argparse.ArgumentParser(
        prog="csv_to_beslistabel.py",
        description=(
            "Convert a CSV decision table into the canonical §12 RegelSpraak "
            "Beslistabel markdown-pipe-table .rgs fragment.\n\n"
            "CSV CONTRACT: optional metadata rows first (each exactly 2 fields, "
            "first cell 'naam' or 'geldig'; default geldig = 'geldig altijd'), "
            "then row 1 = the column-header sentences (doelkolom then 'indien …' "
            "columns) with NO leading row-number column, then the data rows "
            "(value cell + condition cells only; the tool emits the running row "
            "number). Cells pass VERBATIM into the markdown apart from '|' which "
            "is a hard error. UNQUOTED cells are trimmed of edge whitespace; "
            "RFC-4180-QUOTED cells keep their content verbatim (quoted "
            "leading/trailing spaces and separators survive). Separator ';' or "
            "',' is chosen structurally, ';' preferred (';' is the default, and "
            "RegelSpraak enum cells legitimately contain commas). The tool does "
            "NOT validate RegelSpraak content — the resolver is the authority."
        ),
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    p.add_argument("csv_in", nargs="?", default="-",
                   help="input CSV file; omit or '-' to read stdin")
    p.add_argument("--out", default=None,
                   help="output .rgs path; omit to write stdout")
    args = p.parse_args(argv)

    try:
        if args.csv_in == "-":
            text = sys.stdin.read()
        else:
            with open(args.csv_in, "r", encoding="utf-8", newline="") as f:
                text = f.read()
    except OSError as e:
        print(f"fout: kan invoer niet lezen: {e}", file=sys.stderr)
        return 1

    try:
        result = convert(text)
    except ConvertError as e:
        print(f"fout: {e}", file=sys.stderr)
        return 1

    # Only reach here on success: nothing is written to the output stream on error.
    try:
        if args.out is None:
            sys.stdout.write(result)
        else:
            with open(args.out, "w", encoding="utf-8") as f:
                f.write(result)
    except OSError as e:
        print(f"fout: kan uitvoer niet schrijven: {e}", file=sys.stderr)
        return 1

    return 0


if __name__ == "__main__":
    sys.exit(main())
