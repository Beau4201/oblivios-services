//! A custom Arti runtime that lets us "protect" TCP sockets.

use async_trait::async_trait;
use safelog::sensitive;
use std::io::{IoSlice, IoSliceMut, Result as IoResult};
use std::net::SocketAddr;
use std::os::unix::io::AsRawFd;
use std::pin::Pin;
use std::sync::Arc;
use std::task::{ready, Context, Poll};

use futures::{AsyncRead, AsyncWrite, Stream};
use tokio::net::{TcpListener, TcpSocket, TcpStream};
use tokio_util::compat::TokioAsyncReadCompatExt;
use tor_rtcompat::tokio::TokioRustlsRuntime as ArtiRuntime;
use tor_rtcompat::{
    CompoundRuntime, NetStreamListener, NetStreamProvider, StreamOps, TcpConnectOptions,
    TcpListenOptions,
};

use crate::accounting::BandwidthCounter;
use crate::scaffolding::TunnelScaffolding;
use tracing::{trace, warn};

/// A custom Arti runtime for onion-tunnel.
///
/// # Warning
///
/// This runtime does NOT implement `NetStreamProvider::listen`. Attempting to use that function will
/// result in a panic.
pub type OnionTunnelArtiRuntime<S> = CompoundRuntime<
    ArtiRuntime,
    ArtiRuntime,
    ArtiRuntime,
    OnionTunnelArtiNetStreamProvider<S>,
    ArtiRuntime,
    ArtiRuntime,
    ArtiRuntime,
>;

/// Make a new onion-tunnel runtime, using the provided [`TunnelScaffolding`].
pub async fn make_runtime<S: TunnelScaffolding>(scaffolding: Arc<S>) -> OnionTunnelArtiRuntime<S> {
    #[allow(clippy::expect_used)]
    let rt = ArtiRuntime::current().expect("Unable to get runtime");
    let tcp_provider = OnionTunnelArtiNetStreamProvider { scaffolding };
    CompoundRuntime::new(
        rt.clone(),
        rt.clone(),
        rt.clone(),
        tcp_provider,
        rt.clone(),
        rt.clone(),
        rt,
    )
}

pub struct OnionTunnelArtiNetStreamProvider<S> {
    scaffolding: Arc<S>,
}

// manually implemented, since derive tries to have `S: Clone`
impl<S> Clone for OnionTunnelArtiNetStreamProvider<S> {
    fn clone(&self) -> Self {
        Self {
            scaffolding: Arc::clone(&self.scaffolding),
        }
    }
}

pub struct OnionTcpStream {
    inner: TcpStream,
}

pub struct OnionTcpListener {
    inner: TcpListener,
}

impl From<TcpListener> for OnionTcpListener {
    fn from(listener: TcpListener) -> Self {
        OnionTcpListener { inner: listener }
    }
}

#[async_trait]
impl<S: TunnelScaffolding> NetStreamProvider for OnionTunnelArtiNetStreamProvider<S> {
    type Stream = OnionTcpStream;
    type Listener = OnionTcpListener;
    type ConnectOptions = TcpConnectOptions;
    type ListenOptions = TcpListenOptions;

    async fn connect(&self, addr: &SocketAddr, _: &Self::ConnectOptions) -> IoResult<Self::Stream> {
        trace!("New Arti TCP connection to {}", sensitive(addr));
        let socket = if addr.is_ipv4() {
            TcpSocket::new_v4()?
        } else {
            TcpSocket::new_v6()?
        };
        let fd = socket.as_raw_fd();
        trace!("protecting fd {} for socket to {}", fd, sensitive(addr));
        self.scaffolding.protect(fd, addr)?;
        let inner = match socket.connect(*addr).await {
            Ok(x) => {
                trace!("Arti connection to {} successful", sensitive(addr));
                x
            }
            Err(e) => {
                warn!("Arti failed to connect to {}: {}", sensitive(addr), e);
                return Err(e);
            }
        };
        Ok(OnionTcpStream { inner })
    }

    async fn listen(&self, _: &SocketAddr, _: &Self::ListenOptions) -> IoResult<Self::Listener> {
        panic!("attempted to call OnionTunnelNetStreamProvider::listen()!")
    }
}

impl AsyncRead for OnionTcpStream {
    fn poll_read(
        self: Pin<&mut Self>,
        cx: &mut Context<'_>,
        buf: &mut [u8],
    ) -> Poll<IoResult<usize>> {
        let this = Pin::into_inner(self);
        let this = &mut this.inner;
        let ret = AsyncRead::poll_read(Pin::new(&mut this.compat()), cx, buf);
        if let Poll::Ready(Ok(v)) = ret {
            BandwidthCounter::global().on_rx(v as u64);
        }
        ret
    }

    fn poll_read_vectored(
        self: Pin<&mut Self>,
        cx: &mut Context<'_>,
        bufs: &mut [IoSliceMut<'_>],
    ) -> Poll<IoResult<usize>> {
        let this = Pin::into_inner(self);
        let this = &mut this.inner;
        let ret = AsyncRead::poll_read_vectored(Pin::new(&mut this.compat()), cx, bufs);
        if let Poll::Ready(Ok(v)) = ret {
            BandwidthCounter::global().on_rx(v as u64);
        }
        ret
    }
}

impl AsyncWrite for OnionTcpStream {
    fn poll_write(self: Pin<&mut Self>, cx: &mut Context<'_>, buf: &[u8]) -> Poll<IoResult<usize>> {
        let this = Pin::into_inner(self);
        let this = &mut this.inner;
        let ret = AsyncWrite::poll_write(Pin::new(&mut this.compat()), cx, buf);
        if let Poll::Ready(Ok(v)) = ret {
            BandwidthCounter::global().on_tx(v as u64);
        }
        ret
    }

    fn poll_write_vectored(
        self: Pin<&mut Self>,
        cx: &mut Context<'_>,
        bufs: &[IoSlice<'_>],
    ) -> Poll<IoResult<usize>> {
        let this = Pin::into_inner(self);
        let this = &mut this.inner;
        let ret = AsyncWrite::poll_write_vectored(Pin::new(&mut this.compat()), cx, bufs);
        if let Poll::Ready(Ok(v)) = ret {
            BandwidthCounter::global().on_tx(v as u64);
        }
        ret
    }

    fn poll_flush(self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<IoResult<()>> {
        let this = Pin::into_inner(self);
        let this = &mut this.inner;
        AsyncWrite::poll_flush(Pin::new(&mut this.compat()), cx)
    }

    fn poll_close(self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<IoResult<()>> {
        let this = Pin::into_inner(self);
        let this = &mut this.inner;
        AsyncWrite::poll_close(Pin::new(&mut this.compat()), cx)
    }
}

impl StreamOps for OnionTcpStream {}

#[async_trait]
impl NetStreamListener for OnionTcpListener {
    type Stream = OnionTcpStream;
    type Incoming = OnionTcpListener;

    fn incoming(self) -> Self::Incoming {
        OnionTcpListener { inner: self.inner }
    }

    fn local_addr(&self) -> IoResult<SocketAddr> {
        self.inner.local_addr()
    }
}

impl Stream for OnionTcpListener {
    type Item = IoResult<(OnionTcpStream, SocketAddr)>;

    fn poll_next(self: Pin<&mut Self>, ctx: &mut Context<'_>) -> Poll<Option<Self::Item>> {
        match ready!(self.inner.poll_accept(ctx)) {
            Ok((inner, addr)) => Poll::Ready(Some(Ok((OnionTcpStream { inner }, addr)))),
            Err(e) => Poll::Ready(Some(Err(e))),
        }
    }
}
