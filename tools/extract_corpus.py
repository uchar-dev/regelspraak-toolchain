#!/usr/bin/env python3
"""Extract ```regelspraak fenced code blocks from a spec markdown file into
individual .rs fixture files. Used to build the parse corpus for `make grun`
and /verify-corpus. Pure stdlib, no deps."""
import re, sys, os, pathlib

def slug(s, n=6):
    s = re.sub(r'[^a-zA-Z0-9]+', '-', s.strip()).strip('-').lower()
    return '-'.join(s.split('-')[:n]) or 'block'

def extract(md_path, out_dir, prefix):
    text = pathlib.Path(md_path).read_text(encoding='utf-8')
    blocks = re.findall(r'```regelspraak\n(.*?)```', text, re.DOTALL)
    os.makedirs(out_dir, exist_ok=True)
    written = []
    for i, b in enumerate(blocks, 1):
        # Strip markdown blockquote markers ('> ') — some spec examples are quoted.
        b = '\n'.join(re.sub(r'^\s*>\s?', '', ln) for ln in b.splitlines())
        body = b.rstrip('\n') + '\n'
        first = next((ln for ln in body.splitlines() if ln.strip()), 'block')
        name = f'{prefix}-{i:02d}-{slug(first)}.rs'
        p = os.path.join(out_dir, name)
        pathlib.Path(p).write_text(body, encoding='utf-8')
        written.append((p, first.strip()[:60]))
    return written

if __name__ == '__main__':
    md, out, prefix = sys.argv[1], sys.argv[2], sys.argv[3]
    for p, first in extract(md, out, prefix):
        print(f'{p}\t{first}')
