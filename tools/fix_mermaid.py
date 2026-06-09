# -*- coding: utf-8 -*-
"""Repair mermaid blocks damaged by an earlier buggy replace.

Damage: real newlines inside ```mermaid fenced blocks were replaced by the
literal string "<br/>", collapsing each block to one physical line; the
intended label line-breaks (literal backslash-n) were left untouched.

Fix, applied ONLY inside ```mermaid ... ``` fences:
  1. "<br/>"  -> real newline   (undo the damage / restore block structure)
  2. literal backslash-n ("\\n") -> "<br/>"  (apply the original intent:
     mermaid node-label line breaks must use <br/>, not \\n)
"""
import re
import glob

NEWLINE = chr(10)
BACKSLASH_N = chr(92) + "n"  # the two characters: backslash, n


def fix_block(m):
    blk = m.group(0)
    blk = blk.replace("<br/>", NEWLINE)        # step 1: restore newlines
    blk = blk.replace(BACKSLASH_N, "<br/>")    # step 2: label breaks -> <br/>
    return blk


def main():
    changed = 0
    for fp in sorted(glob.glob("docs/design/*.md")):
        s = open(fp, encoding="utf-8").read()
        new = re.sub(r"```mermaid.*?```", fix_block, s, flags=re.DOTALL)
        if new != s:
            open(fp, "w", encoding="utf-8").write(new)
            changed += 1
            print("repaired:", fp)
    print("files repaired:", changed)


if __name__ == "__main__":
    main()
