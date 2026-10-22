// SPDX-License-Identifier: Apache-2.0
//! `exitd`: the exit daemon of Compartments (ADR-184). It prints fixed messages only, never a destination, a host name or
//! a value it was given. Status 2: the command line was refused. Status 1: it could not start or the tunnel stopped.

use exit_daemon::args::{parse_command, Args, Command, ControlSource, Peer, RouteArgs};
use exit_daemon::config::{tunnel_config, DaemonConfig};
use exit_daemon::control::DaemonState;
use exit_daemon::masklist::MaskList;
use exit_daemon::policy::wait_until_needed;
use exit_daemon::routes::helper::{serve_route, RouteHelper};
use exit_daemon::routes::sys::KernelNetlink;
use exit_daemon::server::{accept_replacing, bind_control, inherited_control, serve_connections, wired, Auth};
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
    let command = match parse_command(&argv) {
        Ok(c) => c,
        Err(e) => {
            eprintln!("exitd: {e}");
            return ExitCode::from(2);
        }
    };
    let result = match command {
        Command::Routes(a) => run_routes(a),
        Command::Daemon(a) => {
            // A list that does not load is a refused configuration like a bad quota: status 2, before anything is opened.
            // The message names neither the file nor a row.
            let Ok(list) = MaskList::load(&a.mask_list) else {
                eprintln!("exitd: mask list could not be loaded");
                return ExitCode::from(2);
            };
            run(a, list)
        }
    };
    match result {
        Ok(()) => ExitCode::SUCCESS,
        Err(msg) => {
            eprintln!("exitd: {msg}");
            ExitCode::from(1)
        }
    }
}

fn control_listener(source: &ControlSource) -> Result<UnixListener, &'static str> {
    match source {
        ControlSource::Path(path) => bind_control(path),
        ControlSource::InheritedFd(var) => inherited_control(var),
    }
    .map_err(|_| "control socket could not be created")
}

fn auth_of(peer: &Peer) -> Auth {
    match peer {
        Peer::Uid(uid) => Auth::Uid(*uid),
        Peer::Context(t) => Auth::Context(t.clone()),
    }
}

/// The route helper (`exitd --routes`, ADR-186): one netlink route socket, the control socket and nothing else. No Tor, no
/// tunnel, no internet socket; it writes only the closed rule language of `routes::spec`. A signal ends it by the default
/// action: the rules stay in the kernel and the next helper re-reads them.
fn run_routes(a: RouteArgs) -> Result<(), &'static str> {
    let listener = control_listener(&a.control)?;
    let nl = KernelNetlink::open().map_err(|_| "netlink socket could not be opened")?;
    let helper = Arc::new(Mutex::new(RouteHelper::new(nl)));
    let auth = auth_of(&a.peer);
    let who = auth.clone();
    accept_replacing(listener, &auth, move |stream| {
        let _ = serve_route(stream, &who, &mut helper.lock().unwrap_or_else(PoisonError::into_inner));
    });
    // The accept loop only returns when the listener fails; init restarts the service.
    Err("control socket stopped")
}

fn run(a: Args, list: MaskList) -> Result<(), &'static str> {
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

    // One policy table: the control server writes it (`SetPolicy`, `SetOverrides`), the packet path reads it.
    let (scaffolding, control) = wired(
        Box::new(|proto, local, remote| sockdiag::owner_uid(proto, local, remote)),
        list,
        a.device.clone(),
        a.memory_max,
    );
    let policy = control.policy();
    let rt = tokio::runtime::Builder::new_multi_thread()
        .enable_all()
        .build()
        .map_err(|_| "runtime could not be created")?;
    rt.block_on(async move {
        let mut tunnel = OnionTunnel::new(scaffolding.clone(), &a.device, tunnel_cfg)
            .await
            .map_err(|_| "tunnel could not be created")?;

        let listener = control_listener(&a.control)?;
        let auth = auth_of(&a.peer);
        let control = Arc::new(Mutex::new(control));
        let shutdown = Arc::new(Notify::new());
        let notify = shutdown.clone();
        std::thread::spawn(move || serve_connections(listener, auth, control, move || notify.notify_one()));

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

        // The speed guard reads its counters every few seconds (ADR-188).
        let ticker = scaffolding.clone();
        tokio::spawn(async move {
            loop {
                tokio::time::sleep(exit_daemon::guard::SAMPLE).await;
                ticker.tick_guard(std::time::Instant::now());
            }
        });

        let mut term = tokio::signal::unix::signal(tokio::signal::unix::SignalKind::terminate())
            .map_err(|_| "signal handler could not be installed")?;
        // The Tor client is created but not started (it has contacted nobody) until the exit app's table first has a Tor or
        // masked class; `run` is what bootstraps it. A device whose user asked for nothing never contacts the Tor network.
        let run = async {
            wait_until_needed(policy).await;
            tunnel.run().await
        };
        tokio::select! {
            r = run => {
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
