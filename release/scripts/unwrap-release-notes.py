#!/usr/bin/env python3
# Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license.
"""Join hard-wrapped markdown prose lines into single physical lines.

GitHub renders RELEASE BODIES in comment-style GFM where every single
newline becomes <br>, so notes hard-wrapped at ~100 columns show spurious
mid-sentence line breaks on the release page (seen live on v0.101). This
filter unwraps:

- plain paragraphs,
- list-item continuation lines (indented prose after a `- `/`* `/`1. ` line),
- blockquote continuations (`> text` after `> text`, keeping one `> ` prefix),

and NEVER touches content inside code fences (``` or ~~~), headings (ATX or
setext), table rows, horizontal rules, reference-style link definitions,
HTML blocks, or blank lines. A line that starts a new block (heading, list
marker, table row, HR, link definition, HTML, fence, blockquote after
non-quote) is never joined into the previous line. The transform is
idempotent: running it on its own output is a byte-identical no-op.

Usage: unwrap-release-notes.py < wrapped.md > unwrapped.md
"""
import re
import sys

FENCE = re.compile(r'^\s*(```|~~~)')
BLOCK_START = re.compile(
    r'^\s*('
    r'#{1,6}\s'            # heading
    r'|[-*+]\s'            # bullet list item
    r'|\d{1,3}[.)]\s'      # ordered list item
    r'|\|'                 # table row
    r'|(-\s*){3,}$|(\*\s*){3,}$|(_\s*){3,}$'  # horizontal rule
    r'|={3,}\s*$'          # setext H1 underline
    r'|\[[^\]]*\]:\s'      # reference-style link definition
    r'|<'                  # html block
    r')')
HR = re.compile(r'^\s*((-\s*){3,}|(\*\s*){3,}|(_\s*){3,})$')
SETEXT_UNDERLINE = re.compile(r'^\s*={3,}\s*$')
REF_DEF = re.compile(r'^\s*\[[^\]]*\]:\s')


def unwrap(text):
    out = []
    in_fence = False
    fence_mark = None
    for line in text.splitlines():
        if in_fence:
            out.append(line)
            if FENCE.match(line) and line.strip().startswith(fence_mark):
                in_fence = False
            continue
        fence = FENCE.match(line)
        if fence:
            in_fence = True
            fence_mark = fence.group(1)
            out.append(line)
            continue
        stripped = line.strip()
        if not stripped:
            out.append(line)
            continue
        prev = out[-1] if out else ''
        prev_stripped = prev.strip()
        # Blockquote continuation: '> text' after '> text' joins into one quote line.
        if stripped.startswith('>'):
            content = stripped.lstrip('>').strip()
            if (prev_stripped.startswith('>') and content
                    and not BLOCK_START.match(content)
                    and prev_stripped.lstrip('>').strip()):
                out[-1] = prev.rstrip() + ' ' + content
            else:
                out.append(line)
            continue
        # Plain continuation: joinable when the previous line is prose or list text
        # (not blank, heading, table, blockquote, HR, setext underline, link
        # definition, or fence) and the current line does not start a new block.
        joinable_prev = (prev_stripped
                         and not prev_stripped.startswith('#')
                         and not prev_stripped.startswith('|')
                         and not prev_stripped.startswith('>')
                         and not FENCE.match(prev)
                         and not HR.match(prev)
                         and not SETEXT_UNDERLINE.match(prev)
                         and not REF_DEF.match(prev))
        if joinable_prev and not BLOCK_START.match(line):
            out[-1] = prev.rstrip() + ' ' + stripped
        else:
            out.append(line)
    return '\n'.join(out) + ('\n' if text.endswith('\n') else '')


if __name__ == '__main__':
    sys.stdout.write(unwrap(sys.stdin.read()))
