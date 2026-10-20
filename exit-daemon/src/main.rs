// SPDX-License-Identifier: Apache-2.0
//! `exitd`: the exit daemon of Compartments (ADR-184). It prints fixed messages only, never a destination, a host name or
//! a value it was given. Status 2: the command line was refused. Status 1: it could not start or the tunnel stopped.

use exit_daemon::args::{parse_args, Args};
use exit_daemon::config::{tunnel_config, DaemonConfig};
use exit_daemon::control::DaemonState;
use exit_daemon::scaffolding::ExitScaffolding;
use exit_daemon::server::{serve, Control};
use exit_daemon::sockdiag;
use onion_tunnel::OnionTunnel;
use std::os::unix::fs::PermissionsExt;
use std::os::unix::net::UnixListener;
use std::process::ExitCode;
use std::sync::{Arc, Mutex, PoisonError};
use tokio::sync::Notify;

fn main() -> ExitCode {
    let argv: Vec<String> = std::env::args().skip(1).collect();
    let args = match parse_args(&argv) {
        Ok(a) => a,
        Err(e) => {
            eprintln!("exitd: {e}");
            return ExitCode::from(2);
        }
    };
    match run(args) {
        Ok(()) => ExitCode::SUCCESS,
        Err(msg) => {
            eprintln!("exitd: {msg}");
            ExitCode::from(1)
        }
    }
}

/// Listens on the control socket. One connection at a time; a connection that ends in an error is dropped and the next
/// one is accepted. Returns after a peer asked for shutdown.
fn control_loop(listener: UnixListener, peer_uid: u32, control: Arc<Mutex<Control>>, shutdown: Arc<Notify>) {
    for stream in listener.incoming() {
        let Ok(stream) = stream else { continue };
        let handler = control.clone();
        let _ = serve(stream, peer_uid, |m| handler.lock().unwrap_or_else(PoisonError::into_inner).handle(m));
        if control.lock().unwrap_or_else(PoisonError::into_inner).shutdown_requested() {
            shutdown.notify_one();
            return;
        }
    }
}

fn bind_control(path: &std::path::Path) -> Result<UnixListener, &'static str> {
    // A socket file left by an earlier run would make bind fail; a file that is not ours to remove is an error.
    match std::fs::remove_file(path) {
        Ok(()) => {}
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => {}
        Err(_) => return Err("control socket path is not usable"),
    }
    let listener = UnixListener::bind(path).map_err(|_| "control socket could not be created")?;
    std::fs::set_permissions(path, std::fs::Permissions::from_mode(0o600))
        .map_err(|_| "control socket could not be restricted")?;
    Ok(listener)
}

fn run(a: Args) -> Result<(), &'static str> {
    let cfg = DaemonConfig {
        state_dir: a.state_dir.clone(),
        cache_dir: a.cache_dir.clone(),
        memory_max: a.memory_max,
        disable_fs_permission_checks: a.disable_fs_permission_checks,
    };
    // Arti's own files live in the state directory; the generated configuration sits beside them.
    let tunnel_cfg =
        tunnel_config(&cfg, &a.state_dir.join("exitd-arti.toml")).map_err(|_| "configuration could not be applied")?;

    // Upstream installs the same provider; an earlier installation is not an error.
    let _ = rustls::crypto::aws_lc_rs::default_provider().install_default();

    let scaffolding = ExitScaffolding::new(Box::new(|proto, local, remote| sockdiag::owner_uid(proto, local, remote)));
    let rt = tokio::runtime::Builder::new_multi_thread()
        .enable_all()
        .build()
        .map_err(|_| "runtime could not be created")?;
    rt.block_on(async move {
        let mut tunnel = OnionTunnel::new(scaffolding.clone(), &a.device, tunnel_cfg)
            .await
            .map_err(|_| "tunnel could not be created")?;

        let listener = bind_control(&a.control)?;
        let control = Arc::new(Mutex::new(Control::new(scaffolding.clone(), a.device.clone(), a.memory_max)));
        let shutdown = Arc::new(Notify::new());
        let (peer_uid, notify) = (a.peer_uid, shutdown.clone());
        std::thread::spawn(move || control_loop(listener, peer_uid, control, notify));

        // One fixed line when the tunnel is up: the device scripts and the service wait for it. Nothing else is printed.
        let watch = scaffolding.clone();
        tokio::spawn(async move {
            while watch.state() == DaemonState::Bootstrapping {
                tokio::time::sleep(std::time::Duration::from_millis(500)).await;
            }
            if watch.state() == DaemonState::Up {
                eprintln!("exitd: bootstrapped");
            }
        });

        let mut term = tokio::signal::unix::signal(tokio::signal::unix::SignalKind::terminate())
            .map_err(|_| "signal handler could not be installed")?;
        tokio::select! {
            r = tunnel.run() => {
                scaffolding.set_failed();
                // Returning from the tunnel is never a normal end: the service is restarted by init.
                let _ = r;
                Err("tunnel stopped")
            }
            _ = shutdown.notified() => Ok(()),
            _ = term.recv() => Ok(()),
            _ = tokio::signal::ctrl_c() => Ok(()),
        }
    })
}
