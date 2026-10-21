#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Fails when the production code of the daemon or the vendored library gains or loses a place where a panic can start.

A panic ends the daemon (`panic = "abort"`), so every `unwrap`, `expect`, `panic!`, `unreachable!`, `assert!` and
`debug_assert!` outside the tests is listed in PANIC-SITES.tsv with the reason it cannot be reached by bytes that an app
controls (or the statement that an outage is accepted). A site that is not in the list, and a listed site that is gone, are
errors: the reader of a diff sees every new way to abort. Indexing (`x[i]`) is not listed; it is covered by the fuzz tests of
`src/fuzz.rs`, the parser and DNS fuzz tests of the vendored library, and by the length check that precedes each index.

    tools/audit-panics.py            check
    tools/audit-panics.py --list     print the sites in the format of the list (to start a new entry from)
"""
import collections
import glob
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PATTERN = re.compile(r"\.unwrap\(\)|\.expect\(|panic!|unreachable!|unimplemented!|todo!|\bassert!|\bassert_eq!|\bassert_ne!|debug_assert")
LIB = "third_party/onionmasq/crates/onion-tunnel/src"


def files():
    out = glob.glob(os.path.join(ROOT, "src", "*.rs")) + glob.glob(os.path.join(ROOT, "src", "*", "*.rs"))
    out += glob.glob(os.path.join(ROOT, LIB, "*.rs")) + glob.glob(os.path.join(ROOT, LIB, "*", "*.rs"))
    return sorted(out)


def production(text):
    """The part of a source file before its first `#[cfg(test)]`: the tests of this tree always come last."""
    i = text.find("#[cfg(test)]")
    return text if i < 0 else text[:i]


def sites():
    found = collections.Counter()
    for f in files():
        rel = os.path.relpath(f, ROOT)
        if rel.endswith("fuzz.rs"):
            continue
        for line in production(open(f, encoding="utf-8").read()).split("\n"):
            s = line.strip()
            if s.startswith("//") or not PATTERN.search(s):
                continue
            found[(rel, s)] += 1
    return found


def listed():
    out = collections.Counter()
    reasons = {}
    path = os.path.join(ROOT, "PANIC-SITES.tsv")
    for n, line in enumerate(open(path, encoding="utf-8").read().split("\n"), 1):
        if not line.strip() or line.startswith("#"):
            continue
        parts = line.split("\t")
        if len(parts) != 4 or not parts[3].strip():
            raise SystemExit(f"PANIC-SITES.tsv line {n}: file, count, text and a reason, separated by tabs")
        out[(parts[0], parts[2])] += int(parts[1])
        reasons[(parts[0], parts[2])] = parts[3]
    return out, reasons


def main():
    found = sites()
    if "--list" in sys.argv:
        for (f, t), n in sorted(found.items()):
            print(f"{f}\t{n}\t{t}\tTODO: why this cannot be reached")
        return 0
    want, _ = listed()
    bad = 0
    for key, n in sorted(found.items()):
        if want.get(key, 0) != n:
            print(f"NOT LISTED ({n} found, {want.get(key, 0)} listed): {key[0]}: {key[1]}")
            bad += 1
    for key, n in sorted(want.items()):
        if key not in found:
            print(f"GONE from the source ({n} listed): {key[0]}: {key[1]}")
            bad += 1
    if bad:
        return 1
    print(f"panic sites: {sum(found.values())} in {len(found)} lines, all listed with a reason")
    return 0


if __name__ == "__main__":
    sys.exit(main())
