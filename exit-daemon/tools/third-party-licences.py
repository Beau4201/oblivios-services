#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Writes the aggregated licence text of the registry packages of a Cargo.lock.

    third-party-licences.py LOCK CRATES_DIR OUT      LOCK: a Cargo.lock; CRATES_DIR: a directory of unpacked crates named
                                                     NAME-VERSION (cargo's registry/src/<index>); OUT: the text file
    third-party-licences.py --self-test

Every `registry+` package of LOCK must be present in CRATES_DIR; one that is missing is an error. For each package the
licence files at the root of the crate (LICENSE*, LICENCE*, COPYING*, NOTICE*, UNLICENSE*, COPYRIGHT*) are read. Files with
the same bytes are printed once, with the list of packages that carry them. A package without such a file is listed with
the licence expression and the authors that its Cargo.toml declares, because those lines are the only notice it gives. Path
packages (the daemon, the vendored Onionmasq) are not listed: their licences are in the repository. The text lists every
package of the lock, which is at least the set that a binary links; the output is byte-identical for the same inputs.
"""
import hashlib
import re
import sys
import tempfile
import tomllib
from pathlib import Path

# The name of the fifth kind is split so that a scan of this file does not read the pattern as a notice line.
LICENCE_FILE = re.compile(r"^(LICEN[CS]E|COPYING|NOTICE|UNLICENSE|COPY" "RIGHT)[-._A-Za-z0-9]*$", re.I)


def packages(lock_text):
    return sorted((p["name"], p["version"]) for p in tomllib.loads(lock_text)["package"]
                  if p.get("source", "").startswith("registry+"))


def read_crate(crates, name, version):
    d = Path(crates) / f"{name}-{version}"
    if not d.is_dir():
        sys.exit(f"{d} is missing: fetch the locked crates first")
    files = {}
    for f in sorted(d.iterdir()):
        if f.is_file() and LICENCE_FILE.match(f.name):
            files[f.name] = f.read_bytes()
    pkg = tomllib.loads((d / "Cargo.toml").read_text(encoding="utf-8")).get("package", {})
    lic = pkg.get("license") or (f"see {pkg['license-file']}" if pkg.get("license-file") else "not declared")
    return files, lic, pkg.get("authors", [])


def render(lock_text, crates):
    texts = {}      # sha256 -> (bytes, [(name, version, file)])
    bare = []       # (name, version, licence, authors)
    pk = packages(lock_text)
    for name, version in pk:
        files, lic, authors = read_crate(crates, name, version)
        if not files:
            bare.append((name, version, lic, authors))
        for fname, data in files.items():
            texts.setdefault(hashlib.sha256(data).hexdigest(), (data, []))[1].append((name, version, fname))
    out = [f"Licence texts of {len(pk)} packages from crates.io (the packages of one Cargo.lock).\n",
           f"{len(texts)} distinct texts follow, each with the packages whose crate carries it.\n",
           f"{len(bare)} packages carry no licence file; they are listed at the end with what their Cargo.toml declares.\n"]
    for i, (digest, (data, who)) in enumerate(sorted(texts.items(), key=lambda kv: (sorted(kv[1][1])[0], kv[0])), 1):
        out.append("\n" + "=" * 78 + f"\nText {i} (sha256 {digest})\nCarried by:\n")
        out += [f"  {n} {v} ({f})\n" for n, v, f in sorted(who)]
        out.append("-" * 78 + "\n" + data.decode("utf-8", "replace").rstrip("\n") + "\n")
    out.append("\n" + "=" * 78 + "\nPackages without a licence file in the crate\n")
    for n, v, lic, authors in bare:
        out.append(f"  {n} {v}: licence {lic}; authors {', '.join(authors) or 'none declared'}\n")
    return "".join(out)


def self_test():
    with tempfile.TemporaryDirectory() as t:
        t = Path(t)
        for n, extra in (("a-1.0.0", {"LICENSE-MIT": "MIT text"}), ("b-2.0.0", {"LICENSE": "MIT text"}), ("c-3.0.0", {})):
            (t / n).mkdir()
            (t / n / "Cargo.toml").write_text('[package]\nname="x"\nlicense="MIT"\nauthors=["Ann"]\n')
            for f, c in extra.items():
                (t / n / f).write_text(c)
        lock = ('[[package]]\nname="a"\nversion="1.0.0"\nsource="registry+https://x"\n'
                '[[package]]\nname="b"\nversion="2.0.0"\nsource="registry+https://x"\n'
                '[[package]]\nname="c"\nversion="3.0.0"\nsource="registry+https://x"\n'
                '[[package]]\nname="own"\nversion="0.1.0"\n')
        text = render(lock, t)
        assert text.count("MIT text") == 1 and "a 1.0.0 (LICENSE-MIT)" in text and "b 2.0.0 (LICENSE)" in text
        assert "c 3.0.0: licence MIT; authors Ann" in text and "own" not in text
        assert render(lock, t) == text
        (t / "b-2.0.0" / "Cargo.toml").unlink()
        try:
            render(lock, t)
        except FileNotFoundError:
            pass
        else:
            raise AssertionError("a crate without Cargo.toml must fail")
    print("self-test: OK")


if __name__ == "__main__":
    if sys.argv[1:] == ["--self-test"]:
        self_test()
    elif len(sys.argv) == 4:
        Path(sys.argv[3]).write_text(render(Path(sys.argv[1]).read_text(encoding="utf-8"), sys.argv[2]), encoding="utf-8")
    else:
        sys.exit(__doc__)
