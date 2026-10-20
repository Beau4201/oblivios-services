use std::process::Command;

fn main() {
    let output = match Command::new("git")
        .arg("log")
        .arg("-1")
        .arg("--format=%H")
        .output()
    {
        Ok(output) if output.status.success() => {
            let hash = String::from_utf8(output.stdout).unwrap();
            format!("git-{hash}")
        }
        _ => "crates-io".to_string(),
    };
    println!("cargo:rustc-env=ONIONTUNNEL_COMMIT_HASH={output}");
}
