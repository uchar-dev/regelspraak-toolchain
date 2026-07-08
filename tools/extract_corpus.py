#!/usr/bin/env python3
"""Extract ```regelspraak fenced code blocks from a spec markdown file into
individual .rgs fixture files. Used to build the parse corpus for `make grun`
and /verify-corpus. Pure stdlib, no deps."""
import re, sys, os, pathlib

def slug(s, n=6):
    s = re.sub(r'[^a-zA-Z0-9]+', '-', s.strip()).strip('-').lower()
    return '-'.join(s.split('-')[:n]) or 'block'

def is_ebnf(b):
    """A ```regelspraak block that actually holds Chapter-13 grammar *notation*,
    not a RegelSpraak program. The §13 syntaxdiagrammen are themselves fenced as
    ```regelspraak; their EBNF uses the `::=` production operator, which never
    occurs in a RegelSpraak instance. Such blocks are not parse targets — parsing
    a grammar production as a program is a category error — so they are excluded
    from the corpus (and the count of what was dropped is reported, not hidden)."""
    return '::=' in b

def extract(md_path, out_dir, prefix):
    text = pathlib.Path(md_path).read_text(encoding='utf-8')
    blocks = re.findall(r'```regelspraak\n(.*?)```', text, re.DOTALL)
    os.makedirs(out_dir, exist_ok=True)
    written, skipped = [], 0
    n = 0
    for b in blocks:
        # Strip markdown blockquote markers ('> ') — some spec examples are quoted.
        b = '\n'.join(re.sub(r'^\s*>\s?', '', ln) for ln in b.splitlines())
        if is_ebnf(b):
            skipped += 1
            continue
        n += 1
        # An editorial ellipsis ('…' or a run of '..'/'...') at the very end marks
        # a deliberately-truncated illustration ("… and so on"); it is notation, not
        # RegelSpraak, so the meaningful prefix is kept and the marker dropped.
        b = re.sub(r'\s*(?:…|\.{2,})\s*$', '', b.rstrip())
        body = b.rstrip('\n') + '\n'
        first = next((ln for ln in body.splitlines() if ln.strip()), 'block')
        name = f'{prefix}-{n:02d}-{slug(first)}.rgs'
        p = os.path.join(out_dir, name)
        pathlib.Path(p).write_text(body, encoding='utf-8')
        written.append((p, first.strip()[:60]))
    return written, skipped

if __name__ == '__main__':
    md, out, prefix = sys.argv[1], sys.argv[2], sys.argv[3]
    written, skipped = extract(md, out, prefix)
    for p, first in written:
        print(f'{p}\t{first}')
    if skipped:
        print(f'# skipped {skipped} EBNF/notation block(s) (contain "::=")', file=sys.stderr)
