use std::sync::{Arc, Mutex};
use std::{
    pin::Pin,
    task::{Context, Poll},
};

use crate::{OnionTunnel, TunnelScaffolding};
use bytes::Bytes;
use futures::Stream;
use smoltcp::iface::SocketSet;
use smoltcp::socket::udp::{BindError, SendError, UdpMetadata};
use smoltcp::socket::{tcp, udp};
use smoltcp::{iface::SocketHandle, wire::IpEndpoint};
use tokio::io::{AsyncRead, AsyncWrite, ReadBuf};
use tokio::sync::Notify;

/// A wrapper for a `smoltcp` `TcpSocket`.
#[derive(Clone)]
pub struct TcpSocket {
    handle: SocketHandle,
    socket_set: Arc<Mutex<SocketSet<'static>>>,
    notify: Arc<Notify>,
}

/// A wrapper for a `smoltcp` `UdpSocket`.
pub struct UdpSocket {
    handle: SocketHandle,
    socket_set: Arc<Mutex<SocketSet<'static>>>,
    notify: Arc<Notify>,
}

impl UdpSocket {
    pub fn new<S: TunnelScaffolding, D>(
        tunnel: &OnionTunnel<S, D>,
        s: udp::Socket<'static>,
    ) -> Self {
        let notify = tunnel.poll_notify.clone();
        let socket_set = tunnel.socket_set.clone();
        #[allow(clippy::unwrap_used)]
        let handle = socket_set.lock().unwrap().add(s);
        Self {
            handle,
            notify,
            socket_set,
        }
    }

    fn with<R>(&mut self, f: impl FnOnce(&mut udp::Socket) -> R) -> R {
        #[allow(clippy::unwrap_used)]
        f(self
            .socket_set
            .lock()
            .unwrap()
            .get_mut::<udp::Socket>(self.handle))
    }

    #[allow(dead_code)]
    pub fn bind(&mut self, addr: smoltcp::wire::IpAddress, port: u16) -> Result<(), BindError> {
        self.with(|s| s.bind((addr, port)))
    }

    /// FreeholdOS patch 0004: accept datagrams for this port whatever the destination address is.
    pub fn bind_any(&mut self, port: u16) -> Result<(), BindError> {
        self.with(|s| s.bind(port))
    }

    /// Send a datagram. With `local_address` set in the metadata the datagram leaves from that address, which is how an
    /// answer carries the address the query was sent to.
    pub fn send(&mut self, payload: &[u8], meta: impl Into<UdpMetadata>) -> Result<(), SendError> {
        let ret = self.with(|s| s.send_slice(payload, meta));
        self.notify.notify_one();
        ret
    }
}

impl Drop for UdpSocket {
    fn drop(&mut self) {
        #[allow(clippy::unwrap_used)]
        self.socket_set.lock().unwrap().remove(self.handle);
    }
}

impl Stream for UdpSocket {
    type Item = (Bytes, UdpMetadata);

    fn poll_next(mut self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<Option<Self::Item>> {
        self.with(|s| match s.can_recv() {
            true => match s.recv() {
                Err(_) => Poll::Ready(None),
                Ok((payload, udp_meta)) => Poll::Ready(Some((Bytes::copy_from_slice(payload), udp_meta))),
            },
            false => {
                s.register_recv_waker(cx.waker());
                Poll::Pending
            }
        })
    }
}

impl TcpSocket {
    /// Create a socket wrapper by registering the provided `socket` with the tunnel.
    pub fn new<S: TunnelScaffolding, D>(
        tunnel: &OnionTunnel<S, D>,
        s: tcp::Socket<'static>,
    ) -> Self {
        let notify = tunnel.poll_notify.clone();
        let socket_set = tunnel.socket_set.clone();
        #[allow(clippy::unwrap_used)]
        let handle = socket_set.lock().unwrap().add(s);
        Self {
            handle,
            notify,
            socket_set,
        }
    }

    /// Get the `smoltcp` `SocketHandle` for this socket wrapper.
    pub fn handle(&self) -> SocketHandle {
        self.handle
    }

    /// Run a function `f` with a copy of the actual `smoltcp` socket as argument.
    fn with<R>(&self, f: impl FnOnce(&mut tcp::Socket) -> R) -> R {
        #[allow(clippy::unwrap_used)]
        f(self
            .socket_set
            .lock()
            .unwrap()
            .get_mut::<tcp::Socket>(self.handle))
    }

    /// Get the socket's destination (i.e. the proxy side of the socket)
    ///
    /// NOTE: This might not be populated yet if the socket hasn't finished the 3-way handshake!
    #[allow(dead_code)]
    pub fn dest(&self) -> Option<IpEndpoint> {
        self.with(|s| s.local_endpoint())
    }

    /// Get the socket's source (i.e. the user side of the socket)
    ///
    /// NOTE: This might not be populated yet if the socket hasn't finished the 3-way handshake!
    #[allow(dead_code)]
    pub fn source(&self) -> Option<IpEndpoint> {
        self.with(|s| s.remote_endpoint())
    }

    /// Close the socket.
    pub fn close(&self) {
        self.with(|s| s.close());
        self.notify.notify_one();
    }

    /// Abort the socket, sending a reset packet if necessary.
    pub fn abort(&self) {
        self.with(|s| s.abort());
        self.notify.notify_one();
    }

    pub fn pause_synack(&mut self, paused: bool) {
        self.with(|s| s.pause_synack(paused));
        self.notify.notify_one();
    }
}

/// Extension of smoltcp's `TcpSocket::may_recv` that also accommodates for our hack with listening
/// sockets.
///
/// This is needed because we start trying to read from a smoltcp socket before it's actually
/// finished doing the TCP 3-way handshake. smoltcp's `may_recv` would return false in this case,
/// and cause us to return EOF from `async_read()`, which means connections just die.
fn socket_may_recv_soon(s: &tcp::Socket) -> bool {
    use smoltcp::socket::tcp::State;

    match s.state() {
        State::Listen => true,
        State::SynReceived => true,
        _ => s.may_recv(),
    }
}

/// `may_send` version of `socket_may_recv_soon`.
///
/// See that function's docstring for rationale.
fn socket_may_send_soon(s: &tcp::Socket) -> bool {
    use smoltcp::socket::tcp::State;

    match s.state() {
        State::Listen => true,
        State::SynReceived => true,
        _ => s.may_send(),
    }
}

impl AsyncRead for TcpSocket {
    fn poll_read(
        self: Pin<&mut Self>,
        cx: &mut Context<'_>,
        buf: &mut ReadBuf<'_>,
    ) -> Poll<std::io::Result<()>> {
        self.with(|s| match s.can_recv() {
            true => {
                let rbuf = s.recv_slice(buf.initialize_unfilled());
                match rbuf {
                    Err(e) => Poll::Ready(Err(std::io::Error::other(format!("smoltcp: {e:?}")))),
                    Ok(n) => {
                        if n > 0 {
                            buf.set_filled(n);
                            Poll::Ready(Ok(()))
                        } else {
                            s.register_recv_waker(cx.waker());
                            Poll::Pending
                        }
                    }
                }
            }
            false => {
                // NOTE: We need to check whether the socket is listening here too, since we'll
                //       probably get here before smoltcp actually has a chance to process the SYN.
                if socket_may_recv_soon(s) {
                    s.register_recv_waker(cx.waker());
                    Poll::Pending
                } else {
                    Poll::Ready(Ok(()))
                }
            }
        })
    }
}

impl AsyncWrite for TcpSocket {
    fn poll_write(
        self: Pin<&mut Self>,
        cx: &mut Context<'_>,
        buf: &[u8],
    ) -> Poll<std::io::Result<usize>> {
        let p = self.with(|s| match s.can_send() {
            true => match s.send_slice(buf) {
                Ok(0) => {
                    s.register_send_waker(cx.waker());
                    Poll::Pending
                }
                Ok(n) => Poll::Ready(Ok(n)),
                Err(e) => Poll::Ready(Err(std::io::Error::other(format!("smoltcp: {e:?}")))),
            },
            false => {
                if socket_may_send_soon(s) {
                    s.register_send_waker(cx.waker());
                    Poll::Pending
                } else {
                    Poll::Ready(Ok(0))
                }
            }
        });
        if p.is_ready() {
            // We need to poll in order to process the ingress packets.
            self.notify.notify_one();
        }
        p
    }

    fn poll_flush(self: Pin<&mut Self>, _cx: &mut Context<'_>) -> Poll<std::io::Result<()>> {
        Poll::Ready(Ok(()))
    }

    fn poll_shutdown(self: Pin<&mut Self>, _cx: &mut Context<'_>) -> Poll<std::io::Result<()>> {
        self.close();
        Poll::Ready(Ok(()))
    }
}
