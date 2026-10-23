#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Mutation check of vault-core's host tests (model: exit-daemon/tools/run-mutations.py).

Copies the crate to a temporary directory, applies ONE mutation at a time (an exact, unique string replaced by another)
and runs tools/check-on-server.sh on the copy. A mutation is killed when the tests fail for it. A mutation that does not
compile is NOT counted as killed: it proves nothing about the tests. A mutation whose text is not found exactly once in
its file is an error of this list, not a surviving mutant.

To keep a full run affordable each mutation first runs only the test targets of its file (SCOPE). A mutant that survives
the scoped run is run again against the whole suite before it is declared a survivor.

    VAULT_BUILD_SSH=user@host tools/run-mutations.py [--check-applies] [NAME_FILTER]

--check-applies only checks that every mutation applies to the current sources. The result line is
"N of N mutations killed"; the exit status is 0 only when all were killed.

Left out on purpose, and why:
  * removing the Argon2 parameter validation (kdf.rs, keys.rs, exchange.rs, container.rs): the hostile-parameter tests
    would then really ask for 4 GB of hashing memory on the shared build server;
  * removing fsync calls (store.rs): not observable on a running host;
  * the 100 000-entry limit of Vault::add (vault.rs): filling it takes minutes in a test; the same constant is covered at
    its edge by the import tests;
  * the size check before reading in store.rs read_bounded (the check after reading gives the same answer; the first one
    only keeps a hostile file from being read and from being allocated up front, which a test cannot show without
    allocating tens of gigabytes);
  * the neighbours of two punycode constants (bias 71 for 72, damp 701 for 700): they change no digit of any label whose
    first delta is small, because the thresholds clamp at the same values; the mutants use bias 70 and damp 600, which differ;
  * equivalent mutants (the same answer by another route): the single-character test of checkup's pattern rule (the
    repeated-block rule with a unit of one catches it), the length-below-class-count test of the generator (the attempt cap
    gives the same Corrupt, only slower), and the history, list and field limits of exchange's take_* helpers (the final
    `validate` of every imported entry refuses the same input with the same TooLarge);
  * equivalent mutants: the header bound into the envelope's authenticated data (magic and version are checked first),
    the second validation of the Argon2 parameters in unwrap_with_passphrase, and the bracket check of
    registrable_domain (bare and bracketed IPv6 both end as None).
"""
import os
import shutil
import subprocess
import sys
import tempfile
import time

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

E, K, Y, V, S, I, P, C, G, X, T, A, D, R, W = (
    "src/envelope.rs",
    "src/kdf.rs",
    "src/keys.rs",
    "src/vault.rs",
    "src/store.rs",
    "src/index.rs",
    "src/punycode.rs",
    "src/checkup.rs",
    "src/generator.rs",
    "src/exchange.rs",
    "src/container.rs",
    "src/api.rs",
    "src/codec.rs",
    "src/secret.rs",
    "src/csvimport.rs",
)

SCOPE = {
    E: ["--test", "envelope"],
    K: ["--test", "keys", "--lib"],
    Y: ["--test", "keys", "--lib"],
    V: ["--test", "vault"],
    D: ["--test", "vault"],
    R: ["--test", "vault"],
    S: ["--test", "store"],
    I: ["--test", "index"],
    P: ["--test", "index"],
    C: ["--test", "checkup"],
    G: ["--test", "checkup"],
    X: ["--test", "exchange"],
    T: ["--test", "jni_handles"],
    A: ["--test", "jni_handles"],
    W: ["--test", "csvimport"],
}

# (name, file, old, new)
MUTATIONS = [
    # envelope.rs
    ("envelope: minimum blob length (<=)", E, "blob.len() < HEADER + NONCE + TAG", "blob.len() <= HEADER + NONCE + TAG"),
    ("envelope: size limit (>=)", E, "if blob.len() > MAX_BLOB {", "if blob.len() >= MAX_BLOB {"),
    ("envelope: size limit removed", E, "if blob.len() > MAX_BLOB {", "if false {"),
    ("envelope: magic not checked", E, " || &blob[..4] != MAGIC {", " || false {"),
    ("envelope: version not checked", E, "if version != VERSION {", "if false {"),
    ("envelope: nonce not random", E, "    if OsRng.try_fill_bytes(&mut nonce).is_err() {\n        fatal();\n    }\n    let mut out", "    let mut out"),
    ("envelope: authentication failure called corrupt", E, ".map_err(|_| VaultError::BadCredentials)", ".map_err(|_| VaultError::Corrupt)"),
    ("envelope: aad ignored on open", E, "aad: &bound_aad(&blob[..HEADER], aad) };", "aad: &bound_aad(&blob[..HEADER], b\"\") };"),
    ("envelope: aad ignored on seal", E, "aad: &bound_aad(&out[..HEADER], aad) };", "aad: &bound_aad(&out[..HEADER], b\"\") };"),
    ("envelope: version written as 2", E, "out.extend_from_slice(&VERSION.to_le_bytes());", "out.extend_from_slice(&2u16.to_le_bytes());"),
    # kdf.rs
    ("kdf: zero cost accepted", K, "if self.m_kib == 0 || self.t == 0 || self.p == 0 {", "if self.m_kib == 0 && self.t == 0 && self.p == 0 {"),
    ("kdf: memory limit (>=)", K, "if self.m_kib > MAX_M_KIB ||", "if self.m_kib >= MAX_M_KIB ||"),
    ("kdf: passes limit (>=)", K, "|| self.t > MAX_T ||", "|| self.t >= MAX_T ||"),
    ("kdf: lanes limit (>=)", K, "|| self.p > MAX_P {", "|| self.p >= MAX_P {"),
    ("kdf: passes limit removed", K, "|| self.t > MAX_T ||", "|| false ||"),
    ("kdf: lanes limit removed", K, "|| self.p > MAX_P {", "|| false {"),
    ("kdf: lane memory minimum (<=)", K, "if self.m_kib < 8 * self.p {", "if self.m_kib <= 8 * self.p {"),
    ("kdf: lane memory minimum removed", K, "if self.m_kib < 8 * self.p {", "if false {"),
    ("kdf: Argon2i instead of Argon2id", K, "Algorithm::Argon2id, Version::V0x13", "Algorithm::Argon2i, Version::V0x13"),
    ("kdf: Argon2 version 0x10", K, "Version::V0x13, params)", "Version::V0x10, params)"),
    # keys.rs
    ("keys: hardware secret not mixed in", Y, "Hkdf::<Sha256>::new(Some(hw_secret), &*ikm)", "Hkdf::<Sha256>::new(None, &*ikm)"),
    ("keys: kek info changed", Y, 'b"freeholdos-passwords/passphrase-kek/v1"', 'b"freeholdos-passwords/passphrase-kek/v2"'),
    ("keys: passphrase not mixed in", Y, "let ikm = argon2id(passphrase, salt, params)?;", "let ikm = argon2id(b\"\", salt, params)?;"),
    ("keys: salt not mixed in", Y, "let ikm = argon2id(passphrase, salt, params)?;", "let ikm = argon2id(passphrase, &[0u8; 16], params)?;"),
    # vault.rs
    ("vault: history not capped", V, "e.history.truncate(MAX_HISTORY);", "();"),
    ("vault: history oldest first", V, "e.history.insert(0, (old_password, e.edited_ms));", "e.history.push((old_password, e.edited_ms));"),
    ("vault: password change not detected", V, "if e.password != old_password {", "if false {"),
    ("vault: field limit (<)", V, "let field_ok = |s: &str| s.len() <= MAX_FIELD;", "let field_ok = |s: &str| s.len() < MAX_FIELD;"),
    ("vault: domain list limit (<)", V, "e.domains.len() <= MAX_LIST", "e.domains.len() < MAX_LIST"),
    ("vault: package list limit (<)", V, "e.packages.len() <= MAX_LIST", "e.packages.len() < MAX_LIST"),
    ("vault: history limit (<)", V, "e.history.len() <= MAX_HISTORY", "e.history.len() < MAX_HISTORY"),
    ("vault: size limit on add (>=)", V, "let new_size = self.size - old_len + encoded_len(&entry);\n        if new_size > MAX_PLAIN && new_size > self.size {", "let new_size = self.size - old_len + encoded_len(&entry);\n        if new_size >= MAX_PLAIN && new_size > self.size {"),
    ("vault: size limit on add removed", V, "let new_size = self.size - old_len + encoded_len(&entry);\n        if new_size > MAX_PLAIN && new_size > self.size {", "let new_size = self.size - old_len + encoded_len(&entry);\n        if false {"),
    ("vault: a full vault refuses a replace that does not grow it", V, "let new_size = self.size - old_len + encoded_len(&entry);\n        if new_size > MAX_PLAIN && new_size > self.size {", "let new_size = self.size - old_len + encoded_len(&entry);\n        if new_size > MAX_PLAIN {"),
    ("vault: a replace may grow a full vault", V, "let new_size = self.size - old_len + encoded_len(&entry);\n        if new_size > MAX_PLAIN && new_size > self.size {", "let new_size = self.size - old_len + encoded_len(&entry);\n        if new_size > MAX_PLAIN && new_size > self.size + 1_000_000 {"),
    ("vault: size limit on edit removed", V, "let new_size = self.size - encoded_len(&self.entries[i]) + encoded_len(&e);\n        if new_size > MAX_PLAIN && new_size > self.size {", "let new_size = self.size - encoded_len(&self.entries[i]) + encoded_len(&e);\n        if false {"),
    ("vault: a full vault refuses an edit that does not grow it", V, "let new_size = self.size - encoded_len(&self.entries[i]) + encoded_len(&e);\n        if new_size > MAX_PLAIN && new_size > self.size {", "let new_size = self.size - encoded_len(&self.entries[i]) + encoded_len(&e);\n        if new_size > MAX_PLAIN {"),
    ("vault: an edit may grow a full vault", V, "let new_size = self.size - encoded_len(&self.entries[i]) + encoded_len(&e);\n        if new_size > MAX_PLAIN && new_size > self.size {", "let new_size = self.size - encoded_len(&self.entries[i]) + encoded_len(&e);\n        if new_size > MAX_PLAIN && new_size > self.size + 1_000_000 {"),
    ("vault: size not released on replace", V, "self.size - old_len + encoded_len(&entry)", "self.size + encoded_len(&entry)"),
    ("vault: size not released on edit", V, "self.size - encoded_len(&self.entries[i]) + encoded_len(&e)", "self.size + encoded_len(&e)"),
    ("vault: size not released on delete", V, "self.size -= encoded_len(&self.entries[i]);", "();"),
    ("vault: fill adds no size", V, "if !had {\n                self.size += 8;\n            }", "();"),
    ("vault: headroom for fills removed", V, "pub const MAX_PLAIN: usize = 64 * 1024 * 1024 - 1024 * 1024;", "pub const MAX_PLAIN: usize = 64 * 1024 * 1024;"),
    ("vault: search ignores notes", V, "|| e.notes.to_lowercase().contains(&q)", "|| false"),
    ("vault: search ignores username", V, "|| e.username.to_lowercase().contains(&q)", "|| false"),
    ("vault: search ignores domains", V, "|| e.domains.iter().any(|d| d.to_lowercase().contains(&q))", "|| false"),
    ("vault: search ignores title", V, "|| e.title.to_lowercase().contains(&q)", "|| false"),
    ("vault: search is case sensitive", V, "let q = query.to_lowercase();", "let q = query.to_string();"),
    ("vault: search finds passwords", V, "|| e.notes.to_lowercase().contains(&q)", "|| e.notes.to_lowercase().contains(&q) || e.password.expose().contains(&q)"),
    ("vault: duplicate ids accepted when reading", V, "if !seen.insert(e.id) {\n                return Err(VaultError::Corrupt);\n            }", "seen.insert(e.id);"),
    ("vault: trailing bytes accepted", V, "r.finish()?;\n        Ok(Vault", "Ok(Vault"),
    ("vault: schema not checked", V, "if schema != SCHEMA {", "if false {"),
    ("vault: an edit can change the id", V, "e.id = *id;", "();"),
    ("vault: an add with an existing id duplicates", V, "Some(i) => self.entries[i] = entry,", "Some(_) => self.entries.push(entry),"),
    ("vault: delete reports success for a missing id", V, "            None => false,\n", "            None => true,\n"),
    # codec.rs
    ("codec: field limit (>=)", D, "if len > MAX_FIELD {", "if len >= MAX_FIELD {"),
    ("codec: count must fit strictly", D, "Some(need) if need <= self.remaining() => Ok(n),", "Some(need) if need < self.remaining() => Ok(n),"),
    ("codec: count limit (>=)", D, "if n > max {", "if n >= max {"),
    ("codec: count limit removed", D, "if n > max {", "if false {"),
    ("codec: trailing bytes accepted", D, "if self.remaining() == 0 {", "if true {"),
    ("codec: field length not checked", D, "        if len > MAX_FIELD {\n            return Err(VaultError::TooLarge);\n        }\n        let bytes", "        let bytes"),
    # secret.rs
    ("secret: equality always true", R, "self.0.as_bytes().ct_eq(other.0.as_bytes()).into()", "true"),
    ("secret: equality always false", R, "self.0.as_bytes().ct_eq(other.0.as_bytes()).into()", "false"),
    ("secret: debug prints the secret", R, 'f.write_str("SecretString(<redacted>)")', "f.write_str(&self.0)"),
    # store.rs
    ("store: no lock around the temp file", S, "    let _one_at_a_time = WRITE_LOCK.lock().unwrap_or_else(std::sync::PoisonError::into_inner);\n", ""),
    ("store: file mode", S, ".mode(0o600)", ".mode(0o644)"),
    ("store: rename after the hook", S, "    fs::rename(&tmp, path)?;\n    hook(WriteStep::Renamed)?;", "    hook(WriteStep::Renamed)?;\n    fs::rename(&tmp, path)?;"),
    ("store: stale temp file kept", S, "    match fs::remove_file(&tmp) {\n        Err(e) if e.kind() != io::ErrorKind::NotFound => return Err(e),\n        _ => {}\n    }\n", ""),
    ("store: wrong step reported", S, "hook(WriteStep::TempSynced)?;", "hook(WriteStep::TempCreated)?;"),
    ("store: read limit (>=)", S, "if len > MAX_BLOB as u64 {", "if len >= MAX_BLOB as u64 {"),
    # index.rs
    ("index: domain and package tags equal", I, "Kind::Package => 2,", "Kind::Package => 1,"),
    ("index: tags not sorted", I, "tags.sort_unstable();", "();"),
    ("index: duplicate tags kept", I, "tags.dedup();", "();"),
    ("index: key ignored", I, "let Ok(mut mac) = <Hmac<Sha256> as Mac>::new_from_slice(key) else { fatal() };", "let Ok(mut mac) = <Hmac<Sha256> as Mac>::new_from_slice(&[0u8; 32]) else { fatal() };"),
    ("index: tag count limit removed", I, "if count > MAX_TAGS {", "if false {"),
    ("index: order not checked", I, "if tags.windows(2).any(|w| w[0] >= w[1]) {", "if false {"),
    ("index: duplicates accepted in a file", I, "if tags.windows(2).any(|w| w[0] >= w[1]) {", "if tags.windows(2).any(|w| w[0] > w[1]) {"),
    ("index: trailing bytes accepted", I, "!= Some(bytes.len())", "> Some(bytes.len())"),
    ("index: version not checked", I, "if bytes[4] != VERSION {", "if false {"),
    ("index: magic not checked", I, "|| &bytes[..4] != MAGIC {", "|| false {"),
    ("index: trailing dot kept", I, "let host = host.strip_suffix('.').unwrap_or(host);", "let host = host;"),
    ("index: port range not checked", I, "|| port.parse::<u32>().ok()? > 65_535 {", "|| false {"),
    ("index: port 65535 refused", I, "|| port.parse::<u32>().ok()? > 65_535 {", "|| port.parse::<u32>().ok()? >= 65_535 {"),
    ("index: port digits not checked", I, "!port.bytes().all(|b| b.is_ascii_digit())", "false"),
    ("index: numeric top label accepted", I, "if labels.last()?.bytes().all(|b| b.is_ascii_digit()) {", "if false {"),
    ("index: label of 64 bytes accepted", I, "(1..=63).contains(&label.len())", "(1..=64).contains(&label.len())"),
    ("index: empty label accepted", I, "(1..=63).contains(&label.len())", "(0..=63).contains(&label.len())"),
    ("index: host limit (>254)", I, "if joined.len() > 253 {", "if joined.len() > 254 {"),
    ("index: host limit (>=253)", I, "if joined.len() > 253 {", "if joined.len() >= 253 {"),
    ("index: hyphen first accepted", I, "            && !label.starts_with('-')\n", "\n"),
    ("index: hyphen last accepted", I, "            && !label.ends_with('-');", "            ;"),
    ("index: label characters unrestricted", I, "label.bytes().all(|b| b.is_ascii_lowercase() || b.is_ascii_digit() || b == b'-')", "true"),
    ("index: unicode labels not converted", I, "let label = if label.is_ascii() { label.to_owned() } else { punycode::to_ascii_label(label)? };", "let label = label.to_owned();"),
    ("index: host not lower-cased", I, "for label in host.to_lowercase().split('.') {", "for label in host.split('.') {"),
    ("index: an entry matches by the written host", I, "entry.domains.iter().any(|d| registrable_domain(d).as_deref() == Some(target.as_str()))", "entry.domains.iter().any(|d| d == subject)"),
    ("index: an entry matches any domain", I, "entry.domains.iter().any(|d| registrable_domain(d).as_deref() == Some(target.as_str()))", "!entry.domains.is_empty()"),
    ("index: a package matches by prefix", I, "entry.packages.iter().any(|p| p == subject)", "entry.packages.iter().any(|p| p.starts_with(subject))"),
    ("index: an empty package matches", I, "Kind::Package => !subject.is_empty() && entry.packages", "Kind::Package => entry.packages"),
    ("index: a domain subject without a registrable form matches", I, "            None => false,\n        },\n        Kind::Package", "            None => true,\n        },\n        Kind::Package"),
    ("api: matches returns every entry", A, "s.vault.entries().iter().filter(|e| entry_matches(e, subject, kind))", "s.vault.entries().iter().filter(|_| true)"),
    ("api: matches swaps the kinds", A, "        0 => Kind::Domain,\n        1 => Kind::Package,\n        _ => return Err(Code::BadArgument),", "        0 => Kind::Package,\n        1 => Kind::Domain,\n        _ => return Err(Code::BadArgument),"),
    ("api: matches accepts any kind", A, "        _ => return Err(Code::BadArgument),\n    };\n    with_session(handle, |s| Ok(summaries(s.vault.entries().iter().filter", "        _ => Kind::Domain,\n    };\n    with_session(handle, |s| Ok(summaries(s.vault.entries().iter().filter"),
    # punycode.rs
    ("punycode: initial bias", P, "const INITIAL_BIAS: u32 = 72;", "const INITIAL_BIAS: u32 = 70;"),
    ("punycode: damp", P, "const DAMP: u32 = 700;", "const DAMP: u32 = 600;"),
    ("punycode: skew", P, "const SKEW: u32 = 38;", "const SKEW: u32 = 39;"),
    ("punycode: tmax", P, "const T_MAX: u32 = 26;", "const T_MAX: u32 = 25;"),
    ("punycode: initial n", P, "const INITIAL_N: u32 = 128;", "const INITIAL_N: u32 = 129;"),
    ("punycode: first adaptation", P, "delta = if first { delta / DAMP } else { delta / 2 };", "delta = delta / 2;"),
    ("punycode: digit alphabet", P, "(b'0' + (d - 26) as u8) as char", "(b'0' + (d - 25) as u8) as char"),
    ("punycode: prefix", P, 'String::from("xn--")', 'String::from("xn-")'),
    ("punycode: delimiter", P, "        out.push('-');\n    }\n    let (mut n", "        out.push('_');\n    }\n    let (mut n"),
    # checkup.rs
    ("checkup: eight characters are short", C, "if p.chars().count() < 8 {", "if p.chars().count() <= 8 {"),
    ("checkup: seven characters are fine", C, "if p.chars().count() < 8 {", "if p.chars().count() < 7 {"),
    ("checkup: decorated common words pass", C, "(base.chars().count() >= 4 && common().contains(base))", "false"),
    ("checkup: common list is case sensitive", C, "let lower = password.to_lowercase();\n    let base", "let lower = password.to_string();\n    let base"),
    ("checkup: ascending runs pass", C, "if diffs.iter().all(|&d| d == 1) ||", "if false ||"),
    ("checkup: descending runs pass", C, "|| diffs.iter().all(|&d| d == -1) {", "|| false {"),
    ("checkup: keyboard rows pass", C, "if chars.len() >= 4 && KEYBOARD_ROWS", "if false && KEYBOARD_ROWS"),
    ("checkup: reversed keyboard rows pass", C, "row.contains(s.as_str()) || row.contains(rev.as_str())", "row.contains(s.as_str())"),
    ("checkup: repeated blocks pass", C, "(1..=chars.len() / 2).any(", "(1..=0).any("),
    ("checkup: single use counts as reuse", C, ".filter(|g| g.len() > 1)", ".filter(|g| g.len() >= 1)"),
    ("checkup: empty passwords are a reuse group", C, "        if !p.is_empty() {\n            by_password", "        if true {\n            by_password"),
    ("checkup: groups unsorted", C, "reused.sort_unstable();", "();"),
    ("checkup: ids in a group unsorted", C, "reused.iter_mut().for_each(|g| g.sort_unstable());", "();"),
    ("checkup: strength Weak/Fair step up", C, "b if b < 40.0 => Strength::Weak,", "b if b < 44.0 => Strength::Weak,"),
    ("checkup: strength Weak/Fair step down", C, "b if b < 40.0 => Strength::Weak,", "b if b < 36.0 => Strength::Weak,"),
    ("checkup: strength Fair/Good step up", C, "b if b < 60.0 => Strength::Fair,", "b if b < 62.0 => Strength::Fair,"),
    ("checkup: strength Fair/Good step down", C, "b if b < 60.0 => Strength::Fair,", "b if b < 55.0 => Strength::Fair,"),
    ("checkup: strength Good/Strong step up", C, "b if b < 80.0 => Strength::Good,", "b if b < 85.0 => Strength::Good,"),
    ("checkup: strength Good/Strong step down", C, "b if b < 80.0 => Strength::Good,", "b if b < 79.0 => Strength::Good,"),
    ("checkup: repeats count in full", C, "unique as f64 + 0.5 * (len - unique) as f64", "unique as f64 + 1.0 * (len - unique) as f64"),
    ("checkup: repeats count for nothing", C, "unique as f64 + 0.5 * (len - unique) as f64", "unique as f64 + 0.0 * (len - unique) as f64"),
    ("checkup: lower case not counted", C, "c.is_ascii_lowercase()) {\n        pool += 26;", "c.is_ascii_lowercase()) {\n        pool += 0;"),
    ("checkup: upper case not counted", C, "c.is_ascii_uppercase()) {\n        pool += 26;", "c.is_ascii_uppercase()) {\n        pool += 0;"),
    ("checkup: digits not counted", C, "pool += 10;", "pool += 0;"),
    ("checkup: symbols not counted", C, "pool += 33;", "pool += 0;"),
    ("checkup: non-ASCII not counted", C, "pool += 100;", "pool += 0;"),
    # generator.rs
    ("generator: no rejection", G, "if x < zone {", "if true {"),
    ("generator: zone is not a multiple", G, "let zone = (1u64 << 32) / n * n;", "let zone = (1u64 << 32) / n;"),
    ("generator: length limit (>=)", G, "if opts.length > MAX_LENGTH {", "if opts.length >= MAX_LENGTH {"),
    ("generator: length equal to class count refused", G, "opts.length < classes.len()", "opts.length <= classes.len()"),
    ("generator: one class is enough", G, "classes.iter().all(|set| candidate.iter().any(|c| set.contains(c)))", "classes.iter().any(|set| candidate.iter().any(|c| set.contains(c)))"),
    ("generator: no class check", G, "classes.iter().all(|set| candidate.iter().any(|c| set.contains(c)))", "true"),
    ("generator: lower case ignored", G, "(opts.lower, LOWER)", "(false, LOWER)"),
    ("generator: symbols ignored", G, "(opts.symbols, SYMBOLS)", "(false, SYMBOLS)"),
    # exchange.rs
    ("exchange: an export over the limit of import is made", X, "    if blob.len() > MAX_BLOB + HEADER {\n        return Err(VaultError::TooLarge);\n    }\n    Ok(blob)", "    Ok(blob)"),
    ("exchange: the export limit is one too high", X, "    if blob.len() > MAX_BLOB + HEADER {\n        return Err(VaultError::TooLarge);\n    }\n    Ok(blob)", "    if blob.len() > MAX_BLOB + HEADER + 100_000_000 {\n        return Err(VaultError::TooLarge);\n    }\n    Ok(blob)"),
    ("exchange: file version not checked", X, "if blob[4] != VERSION {", "if false {"),
    ("exchange: magic not checked", X, "|| &blob[..4] != MAGIC {", "|| false {"),
    ("exchange: input limit removed", X, "if blob.len() > MAX_BLOB + HEADER {\n        return Err(VaultError::TooLarge);\n    }\n    if blob.len() < HEADER", "if false {\n        return Err(VaultError::TooLarge);\n    }\n    if blob.len() < HEADER"),
    ("exchange: format name not checked", X, "!= Some(FORMAT)", "== Some(FORMAT)"),
    ("exchange: payload version not checked", X, "Some(1) => {}", "Some(_) => {}"),
    ("exchange: entry limit (>=)", X, "if items.len() > MAX_ENTRIES {", "if items.len() >= MAX_ENTRIES {"),
    ("exchange: entry limit removed", X, "if items.len() > MAX_ENTRIES {", "if false {"),
    ("exchange: history limit (>=)", X, "if items.len() > MAX_HISTORY {", "if items.len() >= MAX_HISTORY {"),
    ("exchange: list limit (>=)", X, "if items.len() > MAX_LIST {", "if items.len() >= MAX_LIST {"),
    ("exchange: field limit (<)", X, "Some(Value::String(s)) if s.len() <= MAX_FIELD => Ok(s),", "Some(Value::String(s)) if s.len() < MAX_FIELD => Ok(s),"),
    ("exchange: duplicate ids kept", X, "while !seen.insert(e.id) {", "while !seen.insert(e.id) && false {"),
    ("exchange: merge overwrites", X, "while work.get(&e.id).is_some() {", "while false {"),
    ("exchange: id hex without padding", X, 'format!("{b:02x}")', 'format!("{b:x}")'),
    ("exchange: id length not checked", X, "if s.len() != 32 || !s.is_ascii() {", "if false {"),
    ("exchange: export key ignores the passphrase", X, "let ikm = argon2id(passphrase, salt, params)?;", "let ikm = argon2id(b\"\", salt, params)?;"),
    # container.rs
    ("container: magic not checked", T, "if r.take(4)? != MAGIC {", "if false {"),
    ("container: version not checked", T, "if version != VERSION {", "if false {"),
    ("container: file limit removed", T, "if file.len() > MAX_FILE {", "if false {"),
    # api.rs
    ("api: handle may exceed a Java long", A, "let h = random_u64() & (i64::MAX as u64);", "let h = random_u64();"),
    ("api: lock keeps the session", A, "    table().remove(&handle);\n    Code::Ok", "    Code::Ok"),
    ("api: empty passphrase accepted", A, "    if passphrase.is_empty() {\n        return Err(Code::BadArgument);\n    }\n", ""),
    ("api: code of a bad credential", A, "Code::BadCredentials => 1,", "Code::BadCredentials => 11,"),
    ("api: code of a dead handle", A, "Code::DeadHandle => 6,", "Code::DeadHandle => 16,"),
    ("api: code of an unsupported version", A, "Code::UnsupportedVersion => 3,", "Code::UnsupportedVersion => 13,"),
    ("api: an error maps to the wrong code", A, "VaultError::TooLarge => Code::TooLarge,", "VaultError::TooLarge => Code::Corrupt,"),
    ("api: edit drops the title", A, "cur.title = e.title;", "();"),
    ("api: edit drops the domains", A, "cur.domains = e.domains;", "();"),
    ("api: edit drops the packages", A, "cur.packages = e.packages;", "();"),
    ("api: edit drops the username", A, "cur.username = e.username;", "();"),
    ("api: edit drops the password", A, "cur.password = e.password;", "();"),
    ("api: edit drops the notes", A, "cur.notes = e.notes;", "();"),
    ("api: edit drops the edit time", A, "cur.edited_ms = e.edited_ms;", "();"),
    ("api: delete of a missing entry succeeds", A, "if s.vault.delete(&id) { Ok(file_of(s)) } else { Err(Code::BadArgument) }", "{ s.vault.delete(&id); Ok(file_of(s)) }"),
    ("api: fill of a missing entry succeeds", A, "        if s.vault.get(&id).is_none() {\n            return Err(Code::BadArgument);\n        }\n", ""),
    ("api: domain kind looks up packages", A, "0 => index.contains(&key, subject, Kind::Domain),", "0 => index.contains(&key, subject, Kind::Package),"),
    ("api: package kind looks up domains", A, "1 => index.contains(&key, subject, Kind::Package),", "1 => index.contains(&key, subject, Kind::Domain),"),
    ("api: unknown kind matches", A, "        _ => false,\n    }\n}\n\npub fn generate", "        _ => index.contains(&key, subject, Kind::Domain),\n    }\n}\n\npub fn generate"),
    ("api: Fair maps to the wrong number", A, "Ok(Strength::Fair) => 1,", "Ok(Strength::Fair) => 2,"),
    ("api: Good maps to the wrong number", A, "Ok(Strength::Good) => 2,", "Ok(Strength::Good) => 1,"),
    ("api: Strong maps to the wrong number", A, "Ok(Strength::Strong) => 3,", "Ok(Strength::Strong) => 2,"),
    ("api: search returns everything", A, "Ok(summaries(s.vault.search(query).into_iter()))", "Ok(summaries(s.vault.entries().iter()))"),
    ("api: weak reason Common", A, "WeakReason::Common => 1,", "WeakReason::Common => 5,"),
    ("api: weak reason Pattern", A, "WeakReason::Pattern => 2,", "WeakReason::Pattern => 5,"),
    ("api: import does not check the handle first", A, "    with_session(handle, |_| Ok(()))?;\n", ""),
    ("api: unlock with the key reads a wrong key length", A, "let vk = VaultKey::from_bytes(key32(vault_key)?);", "let vk = VaultKey::from_bytes([0u8; 32]);"),
    ("api: summary omits the username", A, "    w.str(&e.username);\n    for list", "    w.str(\"\");\n    for list"),
    # csvimport.rs
    ("csv: no limit on the fields of the file", W, "    if rows.1 > MAX_TOTAL_FIELDS {", "    if false {"),
    ("csv: the limit of the file is twice as high", W, "    if rows.1 > MAX_TOTAL_FIELDS {", "    if rows.1 > 2 * MAX_TOTAL_FIELDS {"),
    ("csv: the commas are not counted", W, "                count_field(&mut rows)?;\n                row.push", "                row.push"),
    ("csv: the last field of a row is not counted", W, "    count_field(rows)?;\n    row.push(std::mem::take(field));", "    row.push(std::mem::take(field));"),
    ("csv: no limit on the fields of a record", W, "if row.len() + 2 > MAX_COLUMNS {", "if false {"),
    ("csv: one field too many is allowed", W, "if row.len() + 2 > MAX_COLUMNS {", "if row.len() + 1 > MAX_COLUMNS {"),
    ("csv: the widest record is refused", W, "if row.len() + 2 > MAX_COLUMNS {", "if row.len() + 3 > MAX_COLUMNS {"),
    ('csv: column title alias name removed', W, '"name" | "title" => Col::Title', '"title" => Col::Title'),
    ('csv: column title alias title removed', W, '"name" | "title" => Col::Title', '"name" => Col::Title'),
    ('csv: column url alias url removed', W, '"url" | "login_uri" | "website" | "web site" | "login url" | "origin" => Col::Url', '"login_uri" | "website" | "web site" | "login url" | "origin" => Col::Url'),
    ('csv: column url alias login_uri removed', W, '"url" | "login_uri" | "website" | "web site" | "login url" | "origin" => Col::Url', '"url" | "website" | "web site" | "login url" | "origin" => Col::Url'),
    ('csv: column url alias website removed', W, '"url" | "login_uri" | "website" | "web site" | "login url" | "origin" => Col::Url', '"url" | "login_uri" | "web site" | "login url" | "origin" => Col::Url'),
    ('csv: column url alias web site removed', W, '"url" | "login_uri" | "website" | "web site" | "login url" | "origin" => Col::Url', '"url" | "login_uri" | "website" | "login url" | "origin" => Col::Url'),
    ('csv: column url alias login url removed', W, '"url" | "login_uri" | "website" | "web site" | "login url" | "origin" => Col::Url', '"url" | "login_uri" | "website" | "web site" | "origin" => Col::Url'),
    ('csv: column url alias origin removed', W, '"url" | "login_uri" | "website" | "web site" | "login url" | "origin" => Col::Url', '"url" | "login_uri" | "website" | "web site" | "login url" => Col::Url'),
    ('csv: column user alias username removed', W, '"username" | "login_username" | "user name" | "login" | "user" => Col::User', '"login_username" | "user name" | "login" | "user" => Col::User'),
    ('csv: column user alias login_username removed', W, '"username" | "login_username" | "user name" | "login" | "user" => Col::User', '"username" | "user name" | "login" | "user" => Col::User'),
    ('csv: column user alias user name removed', W, '"username" | "login_username" | "user name" | "login" | "user" => Col::User', '"username" | "login_username" | "login" | "user" => Col::User'),
    ('csv: column user alias login removed', W, '"username" | "login_username" | "user name" | "login" | "user" => Col::User', '"username" | "login_username" | "user name" | "user" => Col::User'),
    ('csv: column user alias user removed', W, '"username" | "login_username" | "user name" | "login" | "user" => Col::User', '"username" | "login_username" | "user name" | "login" => Col::User'),
    ('csv: column email alias email removed', W, '"email" | "e-mail" | "login_email" => Col::Email', '"e-mail" | "login_email" => Col::Email'),
    ('csv: column email alias e-mail removed', W, '"email" | "e-mail" | "login_email" => Col::Email', '"email" | "login_email" => Col::Email'),
    ('csv: column email alias login_email removed', W, '"email" | "e-mail" | "login_email" => Col::Email', '"email" | "e-mail" => Col::Email'),
    ('csv: column password alias password removed', W, '"password" | "login_password" => Col::Pass', '"login_password" => Col::Pass'),
    ('csv: column password alias login_password removed', W, '"password" | "login_password" => Col::Pass', '"password" => Col::Pass'),
    ('csv: column notes alias note removed', W, '"note" | "notes" | "extra" | "comments" => Col::Notes', '"notes" | "extra" | "comments" => Col::Notes'),
    ('csv: column notes alias notes removed', W, '"note" | "notes" | "extra" | "comments" => Col::Notes', '"note" | "extra" | "comments" => Col::Notes'),
    ('csv: column notes alias extra removed', W, '"note" | "notes" | "extra" | "comments" => Col::Notes', '"note" | "notes" | "comments" => Col::Notes'),
    ('csv: column notes alias comments removed', W, '"note" | "notes" | "extra" | "comments" => Col::Notes', '"note" | "notes" | "extra" => Col::Notes'),
    ('csv: the type column is not read', W, '"type" => Col::Kind', '"zz" => Col::Kind'),
    ('csv: the creation time column is not read', W, '"timecreated" => Col::Created', '"zz" => Col::Created'),
    ('csv: the change time column is not read', W, '"timepasswordchanged" => Col::Changed', '"zz" => Col::Changed'),
    ('csv: item kind "" refused', W, 'matches!(kind.as_str(), "" | "login" | "logins" | "password" | "passwords")', 'matches!(kind.as_str(), "login" | "logins" | "password" | "passwords")'),
    ('csv: item kind "login" refused', W, 'matches!(kind.as_str(), "" | "login" | "logins" | "password" | "passwords")', 'matches!(kind.as_str(), "" | "logins" | "password" | "passwords")'),
    ('csv: item kind "logins" refused', W, 'matches!(kind.as_str(), "" | "login" | "logins" | "password" | "passwords")', 'matches!(kind.as_str(), "" | "login" | "password" | "passwords")'),
    ('csv: item kind "password" refused', W, 'matches!(kind.as_str(), "" | "login" | "logins" | "password" | "passwords")', 'matches!(kind.as_str(), "" | "login" | "logins" | "passwords")'),
    ('csv: item kind "passwords" refused', W, 'matches!(kind.as_str(), "" | "login" | "logins" | "password" | "passwords")', 'matches!(kind.as_str(), "" | "login" | "logins" | "password")'),
    ('csv: an empty password is imported', W, '|| password.is_empty() {\n            out.skipped += 1;', '|| false {\n            out.skipped += 1;'),
    ('csv: size limit (>=)', W, 'if input.len() > MAX_CSV {', 'if input.len() >= MAX_CSV {'),
    ('csv: size limit removed', W, 'if input.len() > MAX_CSV {', 'if false {'),
    ('csv: row limit (>=)', W, 'if rows.0.len() > MAX_ENTRIES {', 'if rows.0.len() >= MAX_ENTRIES {'),
    ('csv: row limit removed', W, 'if rows.0.len() > MAX_ENTRIES {', 'if false {'),
    ('csv: a quote opens a field anywhere', W, '\'"\' if field.is_empty() && !was_quoted => {', '\'"\' if !was_quoted => {'),
    ('csv: a quote never opens a field', W, '\'"\' if field.is_empty() && !was_quoted => {', '\'"\' if false => {'),
    ('csv: a doubled quote closes the field', W, 'if chars.peek() == Some(&\'"\') {', 'if false {'),
    ('csv: a quote never closes', W, '                    in_quotes = false;\n', '                    in_quotes = true;\n'),
    ('csv: an unterminated quote is accepted', W, '    if in_quotes {\n        return Err(VaultError::Corrupt);\n    }\n    if !field.is_empty()', '    if false {\n        return Err(VaultError::Corrupt);\n    }\n    if !field.is_empty()'),
    ('csv: the last row without a line break is lost', W, 'if !field.is_empty() || !row.is_empty() || was_quoted {', 'if false {'),
    ('csv: a final quoted empty field is lost', W, 'if !field.is_empty() || !row.is_empty() || was_quoted {', 'if !field.is_empty() || !row.is_empty() {'),
    ('csv: a quoted empty line is a blank line', W, ' && !*was_quoted;', ';'),
    ('csv: a row with an empty first field is a blank line', W, 'let blank = row.len() == 1 &&', 'let blank = row.len() >= 1 &&'),
    ('csv: a later column of the same name wins', W, 'at.entry(col as usize).or_insert(i);', 'at.insert(col as usize, i);'),
    ('csv: a site is listed twice', W, 'Some(Place::Domain(d)) if !domains.contains(&d) => domains.push(d),', 'Some(Place::Domain(d)) => domains.push(d),'),
    ('csv: an app is listed twice', W, 'Some(Place::Package(p)) if !packages.contains(&p) => packages.push(p),', 'Some(Place::Package(p)) => packages.push(p),'),
    ('csv: sites are not limited', W, '        domains.truncate(MAX_LIST);\n', ''),
    ('csv: apps are not limited', W, '        packages.truncate(MAX_LIST);\n', ''),
    ('csv: no e-mail fallback for the username', W, '"" => cell(row, find(Col::Email)),', '"" => "",'),
    ('csv: no title from the address', W, '"" => domains.first().or(packages.first()).map_or("Imported", String::as_str),', '"" => "Imported",'),
    ('csv: no title from the app', W, 'domains.first().or(packages.first())', 'domains.first()'),
    ('csv: fallback title changed', W, 'map_or("Imported", String::as_str)', 'map_or("", String::as_str)'),
    ('csv: a zero time is accepted', W, '.filter(|t| *t > 0)', '.filter(|t| *t >= 0)'),
    ('csv: a negative time is accepted', W, '.filter(|t| *t > 0)', '.filter(|_| true)'),
    ('csv: the change time defaults to the import time', W, 'time(Col::Changed).unwrap_or(created_ms)', 'time(Col::Changed).unwrap_or(now_ms)'),
    ('csv: the creation time defaults to zero', W, 'time(Col::Created).unwrap_or(now_ms)', 'time(Col::Created).unwrap_or(0)'),
    ('csv: an over-long field is imported', W, 'if validate(&entry).is_err() {', 'if false {'),
    ('csv: user info is part of the host', W, '.rsplit(\'@\').next().unwrap_or("")', '.split(\'@\').next().unwrap_or("")'),
    ('csv: a query is part of the host', W, "rest.split(['/', '?', '#'])", "rest.split(['/', '#'])"),
    ('csv: a fragment is part of the host', W, "rest.split(['/', '?', '#'])", "rest.split(['/', '?'])"),
    ('csv: a path is part of the host', W, "rest.split(['/', '?', '#'])", "rest.split(['?', '#'])"),
    ('csv: the scheme is kept', W, 'token.split_once("://").map_or(token, |(_, after)| after)', 'token'),
    ('csv: the Chrome app marker is not read', W, 'if let Some(p) = token.strip_prefix("android://") {', 'if let Some(p) = token.strip_prefix("zz://") {'),
    ('csv: the Bitwarden app marker is not read', W, 'if let Some(p) = token.strip_prefix("androidapp://") {', 'if let Some(p) = token.strip_prefix("zz://") {'),
    ('csv: a package with no dot', W, "        && name.contains('.')\n", '        && true\n'),
    ('csv: a package starting with a dot', W, "        && !name.starts_with('.')\n", '        && true\n'),
    ('csv: a package ending with a dot', W, "        && !name.ends_with('.')\n", '        && true\n'),
    ('csv: a package of any length', W, 'name.len() <= 255', 'name.len() <= 100_000'),
    ('csv: a package with a hyphen', W, " || b == b'_')", " || b == b'_' || b == b'-')"),
    ('csv: a package with a slash is not trimmed', W, "    let name = name.trim_end_matches('/');\n", '    let name = name;\n'),
    ('csv merge: the same account is added twice', W, '        if twin {', '        if false {'),
    ('csv merge: the password is not compared', W, 'o.password == e.password', 'true'),
    ('csv merge: the username is not part of the account', W, '(e.username.clone(), sorted(&e.domains), sorted(&e.packages))', '(String::new(), sorted(&e.domains), sorted(&e.packages))'),
    ('csv merge: the sites are not sorted', W, '        v.sort();\n', '        '),
    ('csv merge: the apps are not part of the account', W, 'sorted(&e.domains), sorted(&e.packages))', 'sorted(&e.domains), Vec::new())'),
    ('csv merge: entries of the file are not remembered', W, '        known.entry(key).or_default().push(work.len());\n', ''),
    ('csv merge: the vault is changed on an error', W, '    *vault = work;\n    Ok((added, duplicates))', '    Ok((added, duplicates))'),
    ('csv merge: duplicates are not counted', W, '            duplicates += 1;', '            duplicates += 0;'),
    ('csv merge: additions are not counted', W, '        added += 1;', '        added += 0;'),
    # api.rs import_csv
    ('api: the csv counts are in another order', A, 'for n in [added, duplicates, parsed.skipped] {', 'for n in [duplicates, added, parsed.skipped] {'),
]


def wait_until_idle() -> None:
    """The build server is shared: a mutation run (a compile and a test run per mutant) starts only while the one-minute
    load is below 8 and 12 GiB of memory are available, and waits otherwise (for a running AOSP build, for instance)."""
    host = os.environ["VAULT_BUILD_SSH"]
    waited = False
    while True:
        r = subprocess.run(
            ["ssh", "-o", "BatchMode=yes", host, "cut -d' ' -f1 /proc/loadavg; awk '/MemAvailable/ {print $2}' /proc/meminfo"],
            capture_output=True, text=True,
        )
        try:
            load, avail_kib = r.stdout.split()
            if float(load) < 8 and int(avail_kib) > 12 * 1024 * 1024:
                if waited:
                    print("server idle again; continuing", flush=True)
                return
        except ValueError:
            pass  # ssh failed or answered something else: wait and try again
        if not waited:
            print("server busy (load or memory); waiting", flush=True)
            waited = True
        time.sleep(120)


def killed(output: str, rc: int) -> str:
    """killed only when the tests ran and failed; anything else (ssh error, no tests, a build error) proves nothing."""
    if rc == 0:
        return "survived"
    if "could not compile" in output or "error[E" in output:
        return "does-not-compile"
    if "test result: FAILED" in output or "panicked at" in output:
        return "killed"
    return "inconclusive"


def main() -> int:
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    only_check = "--check-applies" in sys.argv
    flt = args[0] if args else ""
    muts = [m for m in MUTATIONS if flt in m[0]]
    work = tempfile.mkdtemp(prefix="vault-mutations-")
    copy = os.path.join(work, "vault-core")
    shutil.copytree(ROOT, copy, ignore=shutil.ignore_patterns("target", ".git", "__pycache__"))
    bad = [n for n, f, old, new in muts if open(os.path.join(copy, f)).read().count(old) != 1]
    for n in bad:
        print(f"DOES NOT APPLY exactly once: {n}")
    if bad:
        return 3
    if only_check:
        print(f"all {len(muts)} mutations apply")
        shutil.rmtree(work, ignore_errors=True)
        return 0
    env = dict(os.environ, VAULT_SRC=copy)
    check = os.path.join(ROOT, "tools", "check-on-server.sh")
    wait_until_idle()
    base = subprocess.run(["bash", check], env=env, capture_output=True, text=True)
    if base.returncode != 0:
        print("the unmutated tests do not pass; nothing to measure")
        print(base.stdout[-2000:], base.stderr[-2000:])
        return 4
    results = []
    for name, f, old, new in muts:
        wait_until_idle()
        path = os.path.join(copy, f)
        orig = open(path).read()
        open(path, "w").write(orig.replace(old, new))
        try:
            r = subprocess.run(["bash", check, *SCOPE[f]], env=env, capture_output=True, text=True, timeout=3600)
            verdict = killed(r.stdout + r.stderr, r.returncode)
            if verdict in ("survived", "inconclusive"):
                r = subprocess.run(["bash", check], env=env, capture_output=True, text=True, timeout=3600)
                verdict = killed(r.stdout + r.stderr, r.returncode)
                if verdict == "killed":
                    verdict = "killed-by-suite"
            names = sorted(set(l.split()[1] for l in (r.stdout + r.stderr).splitlines() if l.startswith("test ") and l.endswith("FAILED")))
        except subprocess.TimeoutExpired:
            verdict, names = "timeout", []
        finally:
            open(path, "w").write(orig)
        results.append((name, verdict))
        print(f"{verdict:17} {name}  {' '.join(names[:3])}", flush=True)
    n_killed = sum(1 for _, v in results if v.startswith("killed"))
    print(f"{n_killed} of {len(results)} mutations killed")
    shutil.rmtree(work, ignore_errors=True)
    return 0 if n_killed == len(results) else 1


if __name__ == "__main__":
    sys.exit(main())
