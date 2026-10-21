// SPDX-License-Identifier: Apache-2.0
//! The JNI surface of `org.freeholdos.passwords.core.VaultCore` (a Kotlin `object` with `external fun`s). The only file
//! of the crate that allows `unsafe` (the `#[no_mangle]` exports); it holds no logic: every function turns Java arrays
//! into slices, calls [`crate::api`] and turns the answer back.
//!
//! Conventions. A call that returns bytes returns `[status][payload]`, where status 0 is success and any other value a
//! [`Code`]; a null array means the JVM could not allocate. Calls that return a handle return a positive handle, or
//! `-code`. Secrets are copied out of the Java arrays into buffers that are wiped here; wiping the Java arrays is the
//! caller's job. No code path here may panic (the profile aborts on a panic).
#![allow(unsafe_code, non_snake_case)]
#![deny(clippy::unwrap_used, clippy::expect_used, clippy::indexing_slicing, clippy::panic)]

use std::path::Path;

use ::jni::objects::{JByteArray, JObject, JString};
use ::jni::sys::{jboolean, jbyteArray, jint, jlong, JNI_FALSE, JNI_TRUE};
use ::jni::JNIEnv;
use zeroize::Zeroizing;

use crate::api::{self, Code};
use crate::store::{read_bounded, write_atomic};

type Input = Zeroizing<Vec<u8>>;

fn input(env: &JNIEnv, a: &JByteArray) -> Option<Input> {
    env.convert_byte_array(a).ok().map(Zeroizing::new)
}

fn text(env: &mut JNIEnv, s: &JString) -> Option<String> {
    env.get_string(s).ok().map(String::from)
}

fn reply(env: &JNIEnv, r: Result<Vec<u8>, Code>) -> jbyteArray {
    let mut out = Zeroizing::new(Vec::new());
    match r {
        Ok(payload) => {
            out.push(Code::Ok.code());
            out.extend_from_slice(&payload);
            drop(Zeroizing::new(payload));
        }
        Err(c) => out.push(c.code()),
    }
    env.byte_array_from_slice(&out).map(JByteArray::into_raw).unwrap_or(std::ptr::null_mut())
}

fn handle_reply(r: Result<u64, Code>) -> jlong {
    match r {
        Ok(h) => h as jlong,
        Err(c) => -i64::from(c.code()),
    }
}

fn flag(b: bool) -> jboolean {
    if b {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

/// Unwraps an input array or answers `BadArgument`.
macro_rules! bytes {
    ($env:expr, $a:expr) => {
        match input(&$env, &$a) {
            Some(v) => v,
            None => return reply(&$env, Err(Code::BadArgument)),
        }
    };
}

macro_rules! string {
    ($env:expr, $s:expr) => {
        match text(&mut $env, &$s) {
            Some(v) => v,
            None => return reply(&$env, Err(Code::BadArgument)),
        }
    };
}

#[no_mangle]
pub extern "system" fn Java_org_freeholdos_passwords_core_VaultCore_selfTest<'l>(_env: JNIEnv<'l>, _this: JObject<'l>) -> jboolean {
    flag(api::self_test())
}

/// Reply payload: `u32 file length | file | vault key (32)`.
#[no_mangle]
pub extern "system" fn Java_org_freeholdos_passwords_core_VaultCore_create<'l>(
    env: JNIEnv<'l>,
    _this: JObject<'l>,
    passphrase: JByteArray<'l>,
    hw_secret: JByteArray<'l>,
) -> jbyteArray {
    let passphrase = bytes!(env, passphrase);
    let hw = bytes!(env, hw_secret);
    let r = api::create(&passphrase, &hw).map(|c| {
        let mut p = (c.file.len() as u32).to_le_bytes().to_vec();
        p.extend_from_slice(&c.file);
        p.extend_from_slice(&*c.vault_key);
        p
    });
    reply(&env, r)
}

#[no_mangle]
pub extern "system" fn Java_org_freeholdos_passwords_core_VaultCore_unlockWithPassphrase<'l>(
    env: JNIEnv<'l>,
    _this: JObject<'l>,
    file: JByteArray<'l>,
    passphrase: JByteArray<'l>,
    hw_secret: JByteArray<'l>,
) -> jlong {
    let (Some(file), Some(passphrase), Some(hw)) = (input(&env, &file), input(&env, &passphrase), input(&env, &hw_secret)) else {
        return handle_reply(Err(Code::BadArgument));
    };
    handle_reply(api::unlock_with_passphrase(&file, &passphrase, &hw))
}

#[no_mangle]
pub extern "system" fn Java_org_freeholdos_passwords_core_VaultCore_unlockWithVaultKey<'l>(
    env: JNIEnv<'l>,
    _this: JObject<'l>,
    file: JByteArray<'l>,
    vault_key: JByteArray<'l>,
) -> jlong {
    let (Some(file), Some(vk)) = (input(&env, &file), input(&env, &vault_key)) else {
        return handle_reply(Err(Code::BadArgument));
    };
    handle_reply(api::unlock_with_vault_key(&file, &vk))
}

#[no_mangle]
pub extern "system" fn Java_org_freeholdos_passwords_core_VaultCore_lock<'l>(_env: JNIEnv<'l>, _this: JObject<'l>, handle: jlong) -> jint {
    jint::from(api::lock(handle as u64).code())
}

/// Reply payload: the 32 bytes of the vault key.
#[no_mangle]
pub extern "system" fn Java_org_freeholdos_passwords_core_VaultCore_vaultKey<'l>(env: JNIEnv<'l>, _this: JObject<'l>, handle: jlong) -> jbyteArray {
    reply(&env, api::vault_key(handle as u64).map(|k| k.to_vec()))
}

#[no_mangle]
pub extern "system" fn Java_org_freeholdos_passwords_core_VaultCore_list<'l>(env: JNIEnv<'l>, _this: JObject<'l>, handle: jlong) -> jbyteArray {
    reply(&env, api::list(handle as u64))
}

#[no_mangle]
pub extern "system" fn Java_org_freeholdos_passwords_core_VaultCore_search<'l>(
    mut env: JNIEnv<'l>,
    _this: JObject<'l>,
    handle: jlong,
    query: JString<'l>,
) -> jbyteArray {
    let query = string!(env, query);
    reply(&env, api::search(handle as u64, &query))
}

#[no_mangle]
pub extern "system" fn Java_org_freeholdos_passwords_core_VaultCore_get<'l>(
    env: JNIEnv<'l>,
    _this: JObject<'l>,
    handle: jlong,
    id: JByteArray<'l>,
) -> jbyteArray {
    let id = bytes!(env, id);
    reply(&env, api::get(handle as u64, &id))
}

#[no_mangle]
pub extern "system" fn Java_org_freeholdos_passwords_core_VaultCore_addOrEdit<'l>(
    env: JNIEnv<'l>,
    _this: JObject<'l>,
    handle: jlong,
    entry: JByteArray<'l>,
) -> jbyteArray {
    let entry = bytes!(env, entry);
    reply(&env, api::add_or_edit(handle as u64, &entry))
}

#[no_mangle]
pub extern "system" fn Java_org_freeholdos_passwords_core_VaultCore_delete<'l>(
    env: JNIEnv<'l>,
    _this: JObject<'l>,
    handle: jlong,
    id: JByteArray<'l>,
) -> jbyteArray {
    let id = bytes!(env, id);
    reply(&env, api::delete(handle as u64, &id))
}

#[no_mangle]
pub extern "system" fn Java_org_freeholdos_passwords_core_VaultCore_markFilled<'l>(
    env: JNIEnv<'l>,
    _this: JObject<'l>,
    handle: jlong,
    id: JByteArray<'l>,
    now_ms: jlong,
) -> jbyteArray {
    let id = bytes!(env, id);
    reply(&env, api::mark_filled(handle as u64, &id, now_ms))
}

#[no_mangle]
pub extern "system" fn Java_org_freeholdos_passwords_core_VaultCore_checkup<'l>(env: JNIEnv<'l>, _this: JObject<'l>, handle: jlong) -> jbyteArray {
    reply(&env, api::checkup(handle as u64))
}

#[no_mangle]
pub extern "system" fn Java_org_freeholdos_passwords_core_VaultCore_export<'l>(
    env: JNIEnv<'l>,
    _this: JObject<'l>,
    handle: jlong,
    passphrase: JByteArray<'l>,
) -> jbyteArray {
    let passphrase = bytes!(env, passphrase);
    reply(&env, api::export(handle as u64, &passphrase))
}

/// Reply payload: `u32 entries added | new file`.
#[no_mangle]
pub extern "system" fn Java_org_freeholdos_passwords_core_VaultCore_importEntries<'l>(
    env: JNIEnv<'l>,
    _this: JObject<'l>,
    handle: jlong,
    blob: JByteArray<'l>,
    passphrase: JByteArray<'l>,
) -> jbyteArray {
    let blob = bytes!(env, blob);
    let passphrase = bytes!(env, passphrase);
    reply(&env, api::import_entries(handle as u64, &blob, &passphrase))
}

#[no_mangle]
pub extern "system" fn Java_org_freeholdos_passwords_core_VaultCore_indexBuild<'l>(
    env: JNIEnv<'l>,
    _this: JObject<'l>,
    handle: jlong,
    key: JByteArray<'l>,
) -> jbyteArray {
    let key = bytes!(env, key);
    reply(&env, api::index_build(handle as u64, &key))
}

#[no_mangle]
pub extern "system" fn Java_org_freeholdos_passwords_core_VaultCore_indexContains<'l>(
    mut env: JNIEnv<'l>,
    _this: JObject<'l>,
    index: JByteArray<'l>,
    key: JByteArray<'l>,
    subject: JString<'l>,
    kind: jint,
) -> jboolean {
    let (Some(index), Some(key), Some(subject)) = (input(&env, &index), input(&env, &key), text(&mut env, &subject)) else {
        return JNI_FALSE;
    };
    flag(api::index_contains(&index, &key, &subject, kind))
}

#[no_mangle]
pub extern "system" fn Java_org_freeholdos_passwords_core_VaultCore_generate<'l>(
    env: JNIEnv<'l>,
    _this: JObject<'l>,
    length: jint,
    lower: jboolean,
    upper: jboolean,
    digits: jboolean,
    symbols: jboolean,
) -> jbyteArray {
    let r = api::generate(length, lower != JNI_FALSE, upper != JNI_FALSE, digits != JNI_FALSE, symbols != JNI_FALSE);
    reply(&env, r.map(|p| p.to_vec()))
}

#[no_mangle]
pub extern "system" fn Java_org_freeholdos_passwords_core_VaultCore_strength<'l>(env: JNIEnv<'l>, _this: JObject<'l>, password: JByteArray<'l>) -> jint {
    match input(&env, &password) {
        Some(p) => api::strength(&p),
        None => 0,
    }
}

/// Replaces the file at `path` atomically (temp file, fsync, rename, directory fsync). Returns a [`Code`] number.
#[no_mangle]
pub extern "system" fn Java_org_freeholdos_passwords_core_VaultCore_writeFileAtomic<'l>(
    mut env: JNIEnv<'l>,
    _this: JObject<'l>,
    path: JString<'l>,
    bytes: JByteArray<'l>,
) -> jint {
    let (Some(path), Some(bytes)) = (text(&mut env, &path), input(&env, &bytes)) else {
        return jint::from(Code::BadArgument.code());
    };
    match write_atomic(Path::new(&path), &bytes, &mut |_| Ok(())) {
        Ok(()) => jint::from(Code::Ok.code()),
        Err(_) => jint::from(Code::Io.code()),
    }
}

/// The bytes of the file at `path`, refused above the size limit before they are read.
#[no_mangle]
pub extern "system" fn Java_org_freeholdos_passwords_core_VaultCore_readFileBounded<'l>(
    mut env: JNIEnv<'l>,
    _this: JObject<'l>,
    path: JString<'l>,
) -> jbyteArray {
    let path = string!(env, path);
    reply(&env, read_bounded(Path::new(&path)).map_err(Code::from))
}
