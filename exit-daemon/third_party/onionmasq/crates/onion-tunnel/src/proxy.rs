use crate::accounting::BandwidthCounter;
use arti_client::config::BoolOrAuto;
use arti_client::TorClient;
use arti_client::{CountryCode, TorAddr};
use arti_client::{DangerouslyIntoTorAddr, StreamPrefs};
use safelog::sensitive;
use smoltcp::wire::{IpAddress, IpEndpoint, IpVersion};
use std::future::Future;
use std::io;
use std::io::Error;
use std::net::{IpAddr, SocketAddr};
use std::os::fd::{AsFd, AsRawFd};
use std::pin::Pin;
use std::sync::Arc;
use std::task::{Context, Poll};
use tokio::io::{AsyncRead, AsyncWrite, ReadBuf};
use tor_proto::client::stream::ClientStreamCtrl;
use tracing::{trace, warn};

use crate::dns::LockedDnsCookies;
use crate::isolation::OnionIsolationKey;
use crate::runtime::OnionTunnelArtiRuntime;
use crate::scaffolding::{ConnectionDetails, FailedConnectionDetails};
use crate::socket::TcpSocket;
use crate::TunnelScaffolding;

// TODO(eta): This is probably very overengineered, and might be better off getting removed...
struct InteractiveCopier<R, W> {
    reader: R,
    writer: W,
    buffer: [u8; 1024],
    start: usize,
    end: usize,
    flushing: bool,
    is_tx: bool,
    counter: Option<Arc<BandwidthCounter>>,
}

impl<R, W> InteractiveCopier<R, W>
where
    R: AsyncRead + Unpin,
    W: AsyncWrite + Unpin,
{
    fn new(reader: R, writer: W, is_tx: bool, counter: Option<Arc<BandwidthCounter>>) -> Self {
        Self {
            reader,
            writer,
            buffer: [0; 1024],
            start: 0,
            end: 0,
            flushing: false,
            is_tx,
            counter,
        }
    }
}

impl<R, W> Future for InteractiveCopier<R, W>
where
    R: AsyncRead + Unpin,
    W: AsyncWrite + Unpin,
{
    type Output = io::Result<()>;

    fn poll(self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<Self::Output> {
        let this = Pin::into_inner(self);
        loop {
            if this.flushing {
                // When the writer's finished flushing, we're done too (reader got closed).
                return Pin::new(&mut this.writer).poll_flush(cx);
            }
            // First, check if we have any data to write out.
            if this.end != 0 {
                let bytes_to_write = this.end - this.start;
                assert_ne!(bytes_to_write, 0);

                match Pin::new(&mut this.writer).poll_write(cx, &this.buffer[this.start..this.end])
                {
                    // Writer closed.
                    Poll::Ready(Ok(0)) => return Poll::Ready(Ok(())),
                    Poll::Ready(Ok(v)) => {
                        if let Some(ref counter) = this.counter {
                            if this.is_tx {
                                counter.on_tx(v as u64);
                            } else {
                                counter.on_rx(v as u64);
                            }
                        }
                        if v == bytes_to_write {
                            // We've written the whole buffer, so clear it out.
                            this.end = 0;
                            this.start = 0;
                        } else {
                            // Advance the start pointer to the start of the bit
                            // we haven't written yet, and try to write that bit.
                            this.start += v;
                            continue;
                        }
                    }
                    Poll::Ready(Err(e)) => return Poll::Ready(Err(e)),
                    Poll::Pending => return Poll::Pending,
                }
            }
            // Once we've written everything out, try and read.
            let mut read_buf = ReadBuf::new(&mut this.buffer);
            match Pin::new(&mut this.reader).poll_read(cx, &mut read_buf) {
                Poll::Ready(Ok(())) => {
                    let bytes_read = read_buf.filled().len();
                    if bytes_read == 0 {
                        // Reader's closed, so just flush out all the written data.
                        this.flushing = true;
                        continue;
                    }
                    this.end = bytes_read;
                    continue;
                }
                Poll::Ready(Err(e)) => return Poll::Ready(Err(e)),
                Poll::Pending => {
                    // Nothing to read, so let's flush.
                    if let Poll::Ready(Err(e)) = Pin::new(&mut this.writer).poll_flush(cx) {
                        return Poll::Ready(Err(e));
                    }
                    return Poll::Pending;
                }
            }
        }
    }
}

pub(crate) struct ArtiProxy<S: TunnelScaffolding> {
    pub(crate) socket: TcpSocket,
    pub(crate) arti: Arc<TorClient<OnionTunnelArtiRuntime<S>>>,
    pub(crate) cookies: LockedDnsCookies,
    pub(crate) isolation_key: OnionIsolationKey,
    pub(crate) country_code: Option<CountryCode>,
    pub(crate) scaffolding: Arc<S>,
    pub(crate) source: IpEndpoint,
    pub(crate) dest: IpEndpoint,
}

impl<S> ArtiProxy<S>
where
    S: TunnelScaffolding,
{
    pub async fn start(&mut self) -> io::Result<()> {
        let dest = self.dest;

        // Follow a completely different code path if we should leak, aka. protect.
        //
        // TODO: It might be better if we branch this method into `start_leak` and
        // `start_arti` instead of having a separate branch at the top that returns
        // early.
        if self.scaffolding.should_protect(self.isolation_key.app_id) {
            trace!("Initating leaking connection to {:?}", sensitive(dest.addr));

            let socket = match dest.addr.version() {
                IpVersion::Ipv4 => tokio::net::TcpSocket::new_v4()?,
                IpVersion::Ipv6 => tokio::net::TcpSocket::new_v6()?,
            };
            let fd = socket.as_fd().as_raw_fd();
            let sa = SocketAddr::new(dest.addr.into(), dest.port);
            trace!("protecting fd {fd} for socket to {}", sensitive(dest.addr));
            self.scaffolding.protect(fd, &sa)?;
            let mut leak = match socket.connect(sa).await {
                Ok(s) => {
                    trace!("Leaking connection to {} successful", sensitive(sa));
                    s
                }
                Err(e) => {
                    warn!("Failed to connect leakingly to {}: {e}", sensitive(sa));
                    return Err(e);
                }
            };

            self.socket.pause_synack(false);

            // No need to call self.scaffolding.on_established() because it
            // expects a Tor circuit; something we obviously do not have here.

            // TODO: This is lots of shared code with everything below the
            // if-statement, it would be nice to share the code instead of having
            // it duplicate.
            let (leak_r, leak_w) = leak.split();
            let counter = self
                .scaffolding
                .get_bandwidth_counter(self.isolation_key.app_id);
            let leak_to_tun =
                InteractiveCopier::new(leak_r, self.socket.clone(), false, counter.clone());
            let tun_to_leak = InteractiveCopier::new(self.socket.clone(), leak_w, true, counter);

            // IMPORTANT!  We need to return early here, everything below is
            // pointless here.
            return tokio::select! {
                result = leak_to_tun => result,
                result = tun_to_leak => result,
            };
        }

        let tor_addr = {
            #[allow(clippy::unwrap_used)]
            let mut cookies = self.cookies.lock().unwrap();
            if let Some(hostname) = cookies.get(Some(self.isolation_key.app_id), &dest.addr) {
                TorAddr::from((hostname, dest.port))
            } else if let Some(hostname) = cookies.get(None, &dest.addr) {
                // Check if there are any fallback DNS entries.
                trace!(
                    "Warning: using fallback isolation key for {}",
                    sensitive(dest.addr)
                );
                TorAddr::from((hostname, dest.port))
            } else {
                // FIXME(eta): This should reset the connection if we *should* have a cookie, but don't.
                let ip_addr: IpAddr = dest.addr.into();
                (ip_addr, dest.port).into_tor_addr_dangerously()
            }
        };

        let tor_addr = match tor_addr {
            Ok(v) => v,
            Err(e) => {
                return Err(Error::other(format!(
                    "failed to resolve {:?} into a TorAddr: {e:?}",
                    sensitive(dest)
                )));
            }
        };

        trace!("Initiating Arti connection to {:?}", sensitive(&tor_addr));

        let mut stream_prefs = StreamPrefs::new();

        // Make the connection prefer the appropriate address family for the kind of address we were
        // given.
        // FIXME(eta): It would be great to make this ipv6_only() etc. However:
        //             - Arti doesn't actually seem to honour this yet(?)
        //             - This will break Happy Eyeballs horrifically since the connection will
        //               succeed and then get instantly reset
        match dest.addr {
            IpAddress::Ipv4(_) => {
                stream_prefs.ipv4_preferred();
            }
            IpAddress::Ipv6(_) => {
                stream_prefs.ipv6_preferred();
            }
        }

        // Isolate with our homegrown isolation token.
        stream_prefs.set_isolation(self.isolation_key);

        // Restrict the country code if we were asked to.
        if let Some(cc) = self.country_code {
            stream_prefs.exit_country(cc);
        }

        stream_prefs.connect_to_onion_services(BoolOrAuto::Explicit(true));

        let arti_stream = match self
            .arti
            .connect_with_prefs(tor_addr.clone(), &stream_prefs)
            .await
        {
            Ok(v) => v,
            Err(e) => {
                self.scaffolding.on_arti_failure(FailedConnectionDetails {
                    proxy_src: self.source,
                    proxy_dst: self.dest,
                    tor_dst: tor_addr.clone(),
                    isolation_key: self.isolation_key.app_id,
                    error: e.clone(),
                });
                return Err(Error::other(format!(
                    "failed to connect to {:?} via Arti: {e}",
                    sensitive(tor_addr)
                )));
            }
        };

        trace!("Got Arti stream for {:?}", sensitive(&tor_addr));
        self.socket.pause_synack(false);

        let tor_circuit = &arti_stream
            .client_stream_ctrl()
            .ok_or(Error::other("failed to obtain client_stream_ctrl"))?
            .tunnel()
            .ok_or(Error::other("failed to obtain circuits"))?;
        let tor_circuit = tor_circuit.as_single_circ()?;

        self.scaffolding.on_established(ConnectionDetails {
            proxy_src: self.source,
            proxy_dst: self.dest,
            tor_dst: &tor_addr,
            isolation_key: self.isolation_key.app_id,
            tor_circuit,
        });

        let (arti_r, arti_w) = arti_stream.split();
        let counter = self
            .scaffolding
            .get_flow_counter(self.isolation_key.app_id, &tor_addr);
        let arti_to_tun =
            InteractiveCopier::new(arti_r, self.socket.clone(), false, counter.clone());
        let tun_to_arti = InteractiveCopier::new(self.socket.clone(), arti_w, true, counter);

        tokio::select! {
            result = arti_to_tun => result,
            result = tun_to_arti => result,
        }
    }
}

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod test {
    use crate::accounting::BandwidthCounter;
    use crate::proxy::InteractiveCopier;
    use rand::{prelude::*, rng};
    use std::cmp;
    use std::io::Error;
    use std::pin::Pin;
    use std::sync::Arc;
    use std::task::{Context, Poll};
    use tokio::io::AsyncWrite;

    /// A writer that only writes up to `bound` bytes every poll_write() call.
    ///
    /// In addition, we'll keep track of the total number of bytes written and panic if it
    /// exceeds `total_expected`. (This is to prevent the copier from writing indefinitely.)
    ///
    /// This exists to catch bugs in `InteractiveCopier`.
    struct BoundedWriter<T> {
        bound: usize,
        total_written: usize,
        total_expected: usize,
        inner: T,
    }

    impl<T: AsyncWrite + Unpin> AsyncWrite for BoundedWriter<T> {
        fn poll_write(
            mut self: Pin<&mut Self>,
            cx: &mut Context<'_>,
            buf: &[u8],
        ) -> Poll<Result<usize, Error>> {
            let end = cmp::min(buf.len(), self.bound);
            match Pin::new(&mut self.inner).poll_write(cx, &buf[0..end]) {
                Poll::Ready(Ok(v)) => {
                    self.total_written += v;
                    if self.total_written > self.total_expected {
                        panic!("wrote too much!");
                    }
                    Poll::Ready(Ok(v))
                }
                x => x,
            }
        }

        fn poll_flush(mut self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<Result<(), Error>> {
            Pin::new(&mut self.inner).poll_flush(cx)
        }

        fn poll_shutdown(
            mut self: Pin<&mut Self>,
            cx: &mut Context<'_>,
        ) -> Poll<Result<(), Error>> {
            Pin::new(&mut self.inner).poll_shutdown(cx)
        }
    }

    #[tokio::test]
    async fn interactive_copier_basic() {
        let mut data = vec![0u8; 4096];
        let mut out = vec![];

        rng().fill_bytes(&mut data);

        InteractiveCopier::new(&data as &[u8], &mut out, false, None)
            .await
            .unwrap();

        assert_eq!(data, out);
    }

    #[tokio::test]
    async fn interactive_copier_counter() {
        let mut data = vec![0u8; 4096];
        let mut out = vec![];

        rng().fill_bytes(&mut data);

        let counter = Arc::new(BandwidthCounter::new());

        InteractiveCopier::new(&data as &[u8], &mut out, false, Some(Arc::clone(&counter)))
            .await
            .unwrap();

        assert_eq!(data, out);
        assert_eq!(counter.bytes_rx(), 4096);
        assert_eq!(counter.bytes_tx(), 0);
    }

    #[tokio::test]
    async fn interactive_copier_bounded() {
        let mut data = vec![0u8; 2048];
        let mut out = vec![];
        let mut writer = BoundedWriter {
            bound: 500,
            total_written: 0,
            total_expected: 2048,
            inner: &mut out,
        };

        rng().fill_bytes(&mut data);

        InteractiveCopier::new(&data as &[u8], &mut writer, false, None)
            .await
            .unwrap();

        assert_eq!(data, out);
    }
}
