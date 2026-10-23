// SPDX-License-Identifier: Apache-2.0
use std::fs;
use std::io;
use std::path::PathBuf;
use vault_core::envelope::MAX_BLOB;
use vault_core::error::VaultError;
use vault_core::keys::VaultKey;
use vault_core::secret::SecretString;
use vault_core::store::{read_bounded, write_atomic, WriteStep};
use vault_core::vault::{Entry, Vault};

struct Dir(PathBuf);
impl Dir {
    fn new(name: &str) -> Dir {
        let p = std::env::temp_dir().join(format!("vault-core-test-{}-{name}", std::process::id()));
        let _ = fs::remove_dir_all(&p);
        fs::create_dir_all(&p).unwrap();
        Dir(p)
    }
}
impl Drop for Dir {
    fn drop(&mut self) {
        let _ = fs::remove_dir_all(&self.0);
    }
}

fn vault_with(title: &str, vk: &VaultKey) -> Vec<u8> {
    let mut v = Vault::new();
    v.add(Entry {
        id: [7; 16],
        title: title.into(),
        domains: vec![],
        packages: vec![],
        username: "u".into(),
        password: SecretString::new("p"),
        notes: String::new(),
        created_ms: 1,
        edited_ms: 1,
        last_filled_ms: None,
        history: vec![],
    })
    .unwrap();
    v.to_sealed(vk)
}

const STEPS: [WriteStep; 4] = [WriteStep::TempCreated, WriteStep::TempSynced, WriteStep::Renamed, WriteStep::DirSynced];

#[test]
fn the_steps_come_in_order() {
    let d = Dir::new("order");
    let path = d.0.join("vault.fhpv");
    let mut seen = Vec::new();
    write_atomic(&path, b"data", &mut |s| {
        seen.push(s);
        Ok(())
    })
    .unwrap();
    assert_eq!(seen, STEPS);
    assert_eq!(fs::read(&path).unwrap(), b"data");
}

#[test]
fn crash_after_every_write_step_leaves_a_readable_file() {
    let vk = VaultKey::generate();
    let old = vault_with("old", &vk);
    let new = vault_with("new", &vk);
    for crash_at in STEPS {
        let d = Dir::new(&format!("crash-{crash_at:?}"));
        let path = d.0.join("vault.fhpv");
        write_atomic(&path, &old, &mut |_| Ok(())).unwrap();

        let r = write_atomic(&path, &new, &mut |s| {
            if s == crash_at {
                Err(io::Error::other("simulated crash"))
            } else {
                Ok(())
            }
        });
        assert!(r.is_err(), "the hook error must surface ({crash_at:?})");

        let on_disk = fs::read(&path).unwrap();
        assert!(on_disk == old || on_disk == new, "a mix of old and new after a crash at {crash_at:?}");
        let expected_new = matches!(crash_at, WriteStep::Renamed | WriteStep::DirSynced);
        assert_eq!(on_disk == new, expected_new, "wrong side of the rename at {crash_at:?}");
        let title = Vault::from_sealed(&on_disk, &vk).unwrap().get(&[7; 16]).unwrap().title.clone();
        assert_eq!(title, if expected_new { "new" } else { "old" });

        // A stale temp file from the crash must not stop the next write.
        write_atomic(&path, &new, &mut |_| Ok(())).unwrap();
        assert_eq!(fs::read(&path).unwrap(), new);
    }
}

#[test]
fn a_first_write_that_crashes_before_the_rename_leaves_no_vault_file() {
    let d = Dir::new("first");
    let path = d.0.join("vault.fhpv");
    let r = write_atomic(&path, b"x", &mut |s| if s == WriteStep::TempSynced { Err(io::Error::other("crash")) } else { Ok(()) });
    assert!(r.is_err());
    assert!(!path.exists());
}

#[cfg(unix)]
#[test]
fn the_file_is_private_to_the_owner() {
    use std::os::unix::fs::PermissionsExt;
    let d = Dir::new("mode");
    let path = d.0.join("vault.fhpv");
    write_atomic(&path, b"x", &mut |_| Ok(())).unwrap();
    assert_eq!(fs::metadata(&path).unwrap().permissions().mode() & 0o777, 0o600);
}

#[test]
fn read_bounded_reads_a_normal_file() {
    let d = Dir::new("read");
    let path = d.0.join("v");
    fs::write(&path, b"hello").unwrap();
    assert_eq!(read_bounded(&path).unwrap(), b"hello");
}

#[test]
fn read_bounded_refuses_an_oversized_file_without_reading_it() {
    let d = Dir::new("big");
    let path = d.0.join("v");
    let f = fs::File::create(&path).unwrap();
    f.set_len(MAX_BLOB as u64 + 1).unwrap(); // sparse: no data is written
    assert_eq!(read_bounded(&path).err(), Some(VaultError::TooLarge));
}

#[test]
fn read_bounded_reports_a_missing_file_as_io() {
    let d = Dir::new("missing");
    assert_eq!(read_bounded(&d.0.join("none")).err(), Some(VaultError::Io));
}

#[test]
fn a_file_of_exactly_the_limit_is_read() {
    let d = Dir::new("exact");
    let path = d.0.join("v");
    let f = fs::File::create(&path).unwrap();
    f.set_len(MAX_BLOB as u64).unwrap();
    assert_eq!(read_bounded(&path).unwrap().len(), MAX_BLOB);
}

/// P-21: two writers of one path used to remove each other's temp file or rename a half-written one over the vault.
#[test]
fn concurrent_writers_of_one_path_never_fail_and_never_mix() {
    let d = Dir::new("concurrent");
    let path = d.0.join("vault.fhpv");
    let a = vec![b'a'; 256 * 1024];
    let b = vec![b'b'; 256 * 1024];
    let mut handles = Vec::new();
    for payload in [a.clone(), b.clone(), a.clone(), b.clone()] {
        let path = path.clone();
        handles.push(std::thread::spawn(move || {
            for _ in 0..150 {
                write_atomic(&path, &payload, &mut |_| Ok(())).unwrap();
            }
        }));
    }
    for h in handles {
        h.join().unwrap();
    }
    let now = fs::read(&path).unwrap();
    assert!(now == a || now == b, "the file is one of the writes, not a mix");
}
