// SPDX-License-Identifier: Apache-2.0
//! `exitd`: the exit daemon of Compartments (ADR-184). It prints fixed messages only, never a destination, a host name or
//! a value it was given. Status 2: the command line was refused. Status 1: it could not start or the tunnel stopped.

use exit_daemon::args::{parse_args, Args, ControlSource, Peer};
use exit_daemon::config::{tunnel_config, DaemonConfig};
use exit_daemon::control::DaemonState;
use exit_daemon::scaffolding::ExitScaffolding;
use exit_daemon::server::{bind_control, inherited_control, serve, Auth, Control};
use exit_daemon::sockdiag;
use onion_tunnel::OnionTunnel;
use std::os::unix::net::UnixListener;
use std::process::ExitCode;
use std::sync::{Arc, Mutex, PoisonError};
use tokio::sync::Notify;

fn main() -> ExitCode {
    // args_os: `args()` panics on a byte sequence that is not UTF-8 and prints it.
    let argv: Result<Vec<String>, _> = std::env::args_os().skip(1).map(|a| a.into_string()).collect();
    let Ok(argv) = argv else {
        eprintln!("exitd: argument is not UTF-8");
        return ExitCode::from(2);
    };
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
fn control_loop(listener: UnixListener, auth: Auth, control: Arc<Mutex<Control>>, shutdown: Arc<Notify>) {
    for stream in listener.incoming() {
        let Ok(stream) = stream else {
            // A persistent accept error (no descriptors left) must not become a busy loop.
            std::thread::sleep(std::time::Duration::from_millis(100));
            continue;
        };
        let handler = control.clone();
        let _ = serve(stream, &auth, |m| handler.lock().unwrap_or_else(PoisonError::into_inner).handle(m));
        if control.lock().unwrap_or_else(PoisonError::into_inner).shutdown_requested() {
            shutdown.notify_one();
            return;
        }
    }
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

    // The one kernel behaviour the daemon cannot work without, checked before anything is opened.
    sockdiag::self_check().map_err(|_| "sock_diag self-check failed")?;

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

        let listener = match &a.control {
            ControlSource::Path(path) => bind_control(path),
            ControlSource::InheritedFd(var) => inherited_control(var),
        }
        .map_err(|_| "control socket could not be created")?;
        let auth = match &a.peer {
            Peer::Uid(uid) => Auth::Uid(*uid),
            Peer::Context(t) => Auth::Context(t.clone()),
        };
        let control = Arc::new(Mutex::new(Control::new(scaffolding.clone(), a.device.clone(), a.memory_max)));
        let shutdown = Arc::new(Notify::new());
        let notify = shutdown.clone();
        std::thread::spawn(move || control_loop(listener, auth, control, notify));

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
