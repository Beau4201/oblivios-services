// SPDX-License-Identifier: Apache-2.0
//! The mask list (ADR-181, ADR-186): which host names an app reaches through Tor when it is masked. The list is a file of
//! the system image, made from the Data Map classifier by `privacy/exit/tools/make-mask-list.py`; the daemon reads no list
//! from anywhere else. Safe Rust only.
//!
//! File format: a first line `exit-mask-list<TAB>1`, then one row per domain, `domain<TAB>company id<TAB>company name`,
//! sorted by domain. A row with company id 0 is a *protected* domain: a name under it is never masked, even when a less
//! specific row would mask it (the most specific row decides, as `Classifier.lookup` of the Data Map does).

use std::collections::HashMap;

/// Most rows a list may have, longest domain and name.
pub const MAX_ENTRIES: usize = 8192;
pub const MAX_DOMAIN: usize = 253;
pub const MAX_NAME: usize = 64;
pub const HEADER: &str = "exit-mask-list\t1";

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ListError {
    /// The first line is not the header.
    Header,
    /// A row that does not have three tab-separated fields, or a company id that is not a number up to 65535.
    Row(usize),
    /// A domain that is not lower-case letters, digits, hyphens and dots in labels of 1 to 63 bytes.
    Domain(usize),
    /// A name that is empty, longer than [`MAX_NAME`] or holds a control character.
    Name(usize),
    Duplicate(usize),
    TooMany,
}

#[derive(Debug, Clone, Default)]
pub struct MaskList {
    rows: HashMap<String, u16>,
}

fn valid_domain(d: &str) -> bool {
    !d.is_empty()
        && d.len() <= MAX_DOMAIN
        && d.split('.').all(|l| {
            !l.is_empty()
                && l.len() <= 63
                && !l.starts_with('-')
                && !l.ends_with('-')
                && l.bytes().all(|b| matches!(b, b'a'..=b'z' | b'0'..=b'9' | b'-'))
        })
}

/// A query name as the list sees it: lower case, one trailing dot removed. `None` for a name that cannot be a host name
/// of the list (too long, empty label, a character outside letters, digits and hyphens), so that it is never masked.
fn normalise(name: &str) -> Option<String> {
    let n = name.strip_suffix('.').unwrap_or(name);
    if n.is_empty() || n.len() > MAX_DOMAIN {
        return None;
    }
    let lower = n.to_ascii_lowercase();
    valid_domain(&lower).then_some(lower)
}

impl MaskList {
    pub fn parse(text: &str) -> Result<MaskList, ListError> {
        let mut lines = text.split('\n');
        if lines.next() != Some(HEADER) {
            return Err(ListError::Header);
        }
        let mut rows: HashMap<String, u16> = HashMap::new();
        for (i, line) in lines.enumerate() {
            let n = i + 2;
            if line.is_empty() {
                continue;
            }
            let mut f = line.split('\t');
            let (Some(domain), Some(id), Some(name), None) = (f.next(), f.next(), f.next(), f.next()) else {
                return Err(ListError::Row(n));
            };
            if !valid_domain(domain) {
                return Err(ListError::Domain(n));
            }
            if id.is_empty() || !id.bytes().all(|b| b.is_ascii_digit()) {
                return Err(ListError::Row(n));
            }
            let id: u16 = id.parse().map_err(|_| ListError::Row(n))?;
            if name.is_empty() || name.len() > MAX_NAME || name.chars().any(|c| c.is_control()) {
                return Err(ListError::Name(n));
            }
            if rows.len() >= MAX_ENTRIES {
                return Err(ListError::TooMany);
            }
            if rows.insert(domain.to_owned(), id).is_some() {
                return Err(ListError::Duplicate(n));
            }
        }
        Ok(MaskList { rows })
    }

    pub fn len(&self) -> usize {
        self.rows.len()
    }

    pub fn is_empty(&self) -> bool {
        self.rows.is_empty()
    }

    /// The company a name is masked for: the row of the longest domain that the name is, or is a subdomain of, decides;
    /// a protected row (company 0) or no row at all gives `None`.
    pub fn classify(&self, name: &str) -> Option<u16> {
        let host = normalise(name)?;
        let mut rest = host.as_str();
        loop {
            if let Some(&id) = self.rows.get(rest) {
                return (id != 0).then_some(id);
            }
            rest = &rest[rest.find('.')? + 1..];
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn list(rows: &str) -> MaskList {
        MaskList::parse(&format!("{HEADER}\n{rows}")).unwrap()
    }

    const SAMPLE: &str = "ads.example.com\t7\tExample Ads\nexample.net\t8\tExample Net\ncdn.example.net\t0\tprotected\ngoogle.com\t0\tprotected\nadservice.google.com\t9\tGoogle ads\n";

    #[test]
    fn a_name_is_the_domain_or_below_it_at_a_label_boundary() {
        let l = list(SAMPLE);
        assert_eq!(l.classify("ads.example.com"), Some(7));
        assert_eq!(l.classify("x.y.ads.example.com"), Some(7));
        assert_eq!(l.classify("example.net"), Some(8));
        assert_eq!(l.classify("sub.example.net"), Some(8));
        assert_eq!(l.classify("notexample.net"), None, "a longer label is another domain");
        assert_eq!(l.classify("xads.example.com"), None);
        assert_eq!(l.classify("example.com"), None, "the parent of a row is not masked");
        assert_eq!(l.classify("net"), None);
        assert_eq!(l.classify("com"), None);
    }

    #[test]
    fn the_most_specific_row_decides_and_a_protected_row_shields_what_is_below_it() {
        let l = list(SAMPLE);
        assert_eq!(l.classify("cdn.example.net"), None, "protected below a masked domain");
        assert_eq!(l.classify("a.cdn.example.net"), None);
        assert_eq!(l.classify("www.example.net"), Some(8));
        assert_eq!(l.classify("www.google.com"), None);
        assert_eq!(l.classify("adservice.google.com"), Some(9), "a masked row below a protected one");
        assert_eq!(l.classify("x.adservice.google.com"), Some(9));
        assert_eq!(l.classify("accounts.google.com"), None);
    }

    #[test]
    fn case_and_one_trailing_dot_do_not_matter() {
        let l = list(SAMPLE);
        assert_eq!(l.classify("ADS.Example.COM"), Some(7));
        assert_eq!(l.classify("ads.example.com."), Some(7));
        assert_eq!(l.classify("ads.example.com.."), None, "two dots are not a name");
        assert_eq!(l.classify(".ads.example.com"), None);
    }

    #[test]
    fn names_that_cannot_be_host_names_are_never_masked() {
        let l = list("example.com\t1\tE\n");
        for bad in ["", ".", "a..example.com", "ex ample.example.com", "a_b.example.com", "é.example.com", "a/b.example.com", "x\0.example.com", "-a.example.com", "a-.example.com"] {
            assert_eq!(l.classify(bad), None, "{bad:?}");
        }
        let long = format!("{}.example.com", "a".repeat(64));
        assert_eq!(l.classify(&long), None, "a label of 64 bytes");
        let ok = format!("{}.example.com", "a".repeat(63));
        assert_eq!(l.classify(&ok), Some(1));
        let too_long = format!("{}.example.com", vec!["a".repeat(60); 5].join("."));
        assert!(too_long.len() > MAX_DOMAIN);
        assert_eq!(l.classify(&too_long), None);
    }

    #[test]
    fn the_parser_checks_every_field_and_names_the_line() {
        let h = |rows: &str| MaskList::parse(&format!("{HEADER}\n{rows}")).unwrap_err();
        assert_eq!(MaskList::parse("").unwrap_err(), ListError::Header);
        assert_eq!(MaskList::parse("exit-mask-list\t2\n").unwrap_err(), ListError::Header);
        assert_eq!(MaskList::parse("x\n").unwrap_err(), ListError::Header);
        assert_eq!(h("a.com\t1\n"), ListError::Row(2));
        assert_eq!(h("a.com\t1\tN\textra\n"), ListError::Row(2));
        assert_eq!(h("a.com\tx\tN\n"), ListError::Row(2));
        assert_eq!(h("a.com\t-1\tN\n"), ListError::Row(2));
        assert_eq!(h("a.com\t65536\tN\n"), ListError::Row(2));
        assert_eq!(h("a.com\t\tN\n"), ListError::Row(2));
        assert_eq!(h("a.com\t1\t\n"), ListError::Name(2));
        assert_eq!(h(&format!("a.com\t1\t{}\n", "n".repeat(65))), ListError::Name(2));
        assert_eq!(h("a.com\t1\tbad\u{7}name\n"), ListError::Name(2));
        assert_eq!(h("A.com\t1\tN\n"), ListError::Domain(2), "upper case is not in a list");
        assert_eq!(h("a..com\t1\tN\n"), ListError::Domain(2));
        assert_eq!(h("é.com\t1\tN\n"), ListError::Domain(2));
        assert_eq!(h("ok.com\t1\tN\nbad domain\t1\tN\n"), ListError::Domain(3));
        assert_eq!(h("a.com\t1\tN\na.com\t2\tM\n"), ListError::Duplicate(3));
        assert!(MaskList::parse(&format!("{HEADER}\na.com\t65535\t{}\n", "n".repeat(64))).is_ok());
    }

    #[test]
    fn the_list_is_bounded() {
        let rows: String = (0..MAX_ENTRIES).map(|i| format!("d{i}.com\t1\tN\n")).collect();
        assert_eq!(MaskList::parse(&format!("{HEADER}\n{rows}")).unwrap().len(), MAX_ENTRIES);
        let more = format!("{rows}over.com\t1\tN\n");
        assert_eq!(MaskList::parse(&format!("{HEADER}\n{more}")).unwrap_err(), ListError::TooMany);
        let long_domain = format!("{}.com", "a".repeat(250));
        assert!(matches!(MaskList::parse(&format!("{HEADER}\n{long_domain}\t1\tN\n")), Err(ListError::Domain(2))));
    }

    #[test]
    fn blank_lines_are_ignored_and_an_empty_list_masks_nothing() {
        let l = MaskList::parse(&format!("{HEADER}\n\na.com\t1\tN\n\n")).unwrap();
        assert_eq!((l.len(), l.classify("a.com")), (1, Some(1)));
        let e = MaskList::parse(&format!("{HEADER}\n")).unwrap();
        assert!(e.is_empty());
        assert_eq!(e.classify("anything.example"), None);
    }

    #[test]
    fn no_input_makes_the_parser_or_the_matcher_panic() {
        let l = list(SAMPLE);
        for byte in 0..=255u8 {
            let s = String::from_utf8_lossy(&[byte, b'.', b'c', b'o', b'm', byte]).into_owned();
            let _ = l.classify(&s);
            let _ = MaskList::parse(&s);
            let _ = MaskList::parse(&format!("{HEADER}\n{s}\t{byte}\t{s}\n"));
        }
        for n in 0..300 {
            let name = format!("{}.com", "a.".repeat(n));
            let _ = l.classify(&name);
        }
    }
}
