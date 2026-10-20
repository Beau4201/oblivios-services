use bbqueue::framed::{FrameConsumer, FrameGrantR, FrameGrantW, FrameProducer};
use bbqueue::BBBuffer;
use smoltcp::phy;
use smoltcp::phy::{DeviceCapabilities, Medium};
use smoltcp::time::Instant;
use std::io;
use std::marker::PhantomPinned;
use std::ops::Deref;

use crate::notify::{NotificationReceiver, NotificationSender};
use std::pin::Pin;
use std::sync::Arc;
use std::task::{Context, Poll};
use tokio::io::{AsyncRead, AsyncWrite, ReadBuf};
use tracing::trace;

mod notify;

/// A hacky backing store for ring buffers.
struct BufferBackingStore<const N: usize> {
    tx_buffer: BBBuffer<N>,
    rx_buffer: BBBuffer<N>,
    _phantom: PhantomPinned,
}

impl<const N: usize> BufferBackingStore<N> {
    /// Allocate two buffers, pin them in place inside an `Arc`, and return two dodgy static
    /// references to the two buffers, together with the pinned `Arc`.
    ///
    /// # Safety
    ///
    /// The caller MUST ensure that the static references are only used while the `Arc` continues
    /// to exist.
    unsafe fn new() -> (&'static BBBuffer<N>, &'static BBBuffer<N>, Pin<Arc<Self>>) {
        let pin = Arc::pin(Self {
            tx_buffer: BBBuffer::new(),
            rx_buffer: BBBuffer::new(),
            _phantom: PhantomPinned,
        });
        // Extend the lifetime of a reference to the pinned content to 'static.
        //
        // SAFETY: The backing store is pinned in place, so this pointer will remain valid for as
        //         long as the `Arc` is not dropped. This is one of the function invariants.
        let self_ref = std::mem::transmute::<&BufferBackingStore<N>, &'static BufferBackingStore<N>>(
            pin.deref(),
        );
        (&self_ref.tx_buffer, &self_ref.rx_buffer, pin)
    }
}

/// The "pollable" half of an [`AsyncDevice`], responsible for copying data between smoltcp and
/// the underlying asynchronous reader/writer `T`.
///
/// This is a [`Stream`](futures::Stream) that yields `Ok(())` every time it moves data around successfully
/// (i.e. receives or sends a packet). You can therefore use this to determine when you should
/// call [`Interface::poll`](smoltcp::iface::Interface::poll)
/// on the smoltcp interface containing the corresponding [`AsyncDevice`].
pub struct PollableAsyncDevice<T, const N: usize> {
    // FIXME(eta): this is far from ideal but I am fed up with pinning and just want it to work
    inner: Pin<Box<T>>,
    rx_buffer: FrameProducer<'static, N>,
    tx_buffer: FrameConsumer<'static, N>,
    notifier: NotificationReceiver,
    mtu: usize,
    broken: bool,
    _bbs: Pin<Arc<BufferBackingStore<N>>>,
}

impl<T, const N: usize> futures::Stream for PollableAsyncDevice<T, N>
where
    T: AsyncRead + AsyncWrite,
{
    type Item = Result<(), io::Error>;

    fn poll_next(self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<Option<Self::Item>> {
        let this = self.get_mut();

        if this.broken {
            trace!("PollableAsyncDevice: polled when broken");
            return Poll::Ready(None);
        }

        // We set this flag if we did something this poll round. This is used to notify
        // the calling code that it should prod the smoltcp stack.
        let mut did_something = false;

        // We check for notifications for the sole purpose of making sure we get woken up when the
        // corresponding device pulls from or pushes to one of the buffers.
        this.notifier
            .check_for_notifications(cx)
            .map_err(|_| io::Error::other("AsyncDevice dropped"))?;

        // Copy packets from the transmit buffer into `inner`.
        while let Some(pkt) = this.tx_buffer.read() {
            assert!(pkt.len() <= this.mtu);
            match this.inner.as_mut().poll_write(cx, &pkt) {
                Poll::Ready(Ok(n)) => {
                    if n != pkt.len() {
                        // That's not supposed to happen: we assume our underlying writer won't
                        // try to split packet writes!
                        this.broken = true;
                        trace!("PollableAsyncDevice: nonconformant writer!");
                        return Poll::Ready(Some(Err(io::Error::other(format!("PollableAsyncDevice's writer wrote only {} bytes of a {} packet in one go", n, pkt.len())))));
                    }
                    // We wrote a packet. Release it to mark it done, and loop to get another.
                    pkt.release();
                    trace!("PollableAsyncDevice: transmitted packet of len {}", n);
                    did_something = true;
                }
                Poll::Ready(Err(e)) => {
                    // Encountered an I/O error!
                    this.broken = true;
                    trace!("PollableAsyncDevice: I/O error on write: {}", e);
                    return Poll::Ready(Some(Err(e)));
                }
                Poll::Pending => {
                    // Not ready to write yet. That's fine; don't release the packet, and break
                    // out, since we aren't going to be able to write any more.
                    break;
                }
            }
        }

        // Move bytes from `inner` into the receive buffer, by trying to reserve a spot in the
        // buffer, and then doing a read.
        if let Ok(mut grant) = this.rx_buffer.grant(this.mtu) {
            assert_eq!(grant.len(), this.mtu);
            let mut readbuf = ReadBuf::new(&mut grant);
            match this.inner.as_mut().poll_read(cx, &mut readbuf) {
                Poll::Ready(Ok(())) => {
                    let n_filled = readbuf.filled().len();
                    grant.commit(n_filled);
                    trace!("PollableAsyncDevice: read packet of len {}", n_filled);
                    did_something = true;
                }
                Poll::Ready(Err(e)) => {
                    // Encountered an I/O error!
                    this.broken = true;
                    trace!("PollableAsyncDevice: I/O error on read: {}", e);
                    return Poll::Ready(Some(Err(e)));
                }
                Poll::Pending => {}
            }
        }

        if did_something {
            // You'd think this is Lisp with the amount of parens!
            Poll::Ready(Some(Ok(())))
        } else {
            trace!("PollableAsyncDevice: all attempts pending");
            Poll::Pending
        }
    }
}

/// A smoltcp [`Device`](phy::Device) that uses a tokio reader/writer for I/O.
///
/// `N` is the size, in octets, of the ring buffer queue used to communicate between this
/// and the [`PollableAsyncDevice`].
pub struct AsyncDevice<const N: usize> {
    tx_buffer: FrameProducer<'static, N>,
    rx_buffer: FrameConsumer<'static, N>,
    sem: NotificationSender,
    medium: Medium,
    mtu: usize,
    _bbs: Pin<Arc<BufferBackingStore<N>>>,
}

impl<const N: usize> AsyncDevice<N> {
    /// Create a new `AsyncDevice` and [`PollableAsyncDevice`], wrapping the provided reader/writer
    /// `rw`.
    ///
    /// If the reader/writer is a TAP device (layer 2), use [`Medium::Ethernet`]; otherwise, use
    /// [`Medium::Ip`]. `mtu` describes the Maximum Transmission Unit of the reader/writer (i.e.
    /// the largest possible packet size).
    ///
    /// # Important usage notes
    ///
    /// - The reader/writer **must** behave like a TUN/TAP device — in that calls to `poll_read()`
    ///   and `poll_write()` must read/write *whole packets* at a time.
    ///   - For example, `poll_read` must either return `Pending`, or read an entire packet
    ///     into the provided buffer (no partial pieces of a packet).
    ///   - Similarly, `poll_write` must be able to write an entire packet in only one call.
    /// - You **must** poll the [`PollableAsyncDevice`] returned by this function in order for
    ///   anything to work. See the docs for that type for more information.
    /// - `N` must be at least twice the value of `mtu`.
    ///
    /// # Panics
    ///
    /// Panics if `N` is not at least twice the value of `mtu`. Additionally, if the reader/writer
    /// does not behave like a TUN/TAP device, the [`PollableAsyncDevice`] will panic.
    pub fn new<T: AsyncRead + AsyncWrite>(
        rw: T,
        medium: Medium,
        mtu: usize,
    ) -> (Self, PollableAsyncDevice<T, N>) {
        // check the const parameter
        let buf_size = std::mem::size_of::<[u8; N]>();
        if (mtu * 2) >= buf_size {
            panic!("AsyncDevice::new() called with too small mtu! N = {buf_size}, mtu = {mtu}");
        }
        // SAFETY: We store the `bbs` in the returned structs, so it won't get dropped for as long
        //         as those structs continue to live.
        let (txb, rxb, bbs) = unsafe { BufferBackingStore::new() };
        let (txb_p, txb_c) = txb.try_split_framed().unwrap();
        let (rxb_p, rxb_c) = rxb.try_split_framed().unwrap();
        let (sem_tx, sem_rx) = NotificationSender::new_pair();
        (
            Self {
                tx_buffer: txb_p,
                rx_buffer: rxb_c,
                sem: sem_tx,
                medium,
                mtu,
                _bbs: bbs.clone(),
            },
            PollableAsyncDevice {
                inner: Box::pin(rw),
                rx_buffer: rxb_p,
                notifier: sem_rx,
                tx_buffer: txb_c,
                mtu,
                broken: false,
                _bbs: bbs,
            },
        )
    }
}

/// The receive token for an [`AsyncDevice`].
pub struct AsyncDeviceRxToken<'a, const N: usize> {
    grant: FrameGrantR<'a, N>,
    sem: &'a NotificationSender,
}

impl<const N: usize> phy::RxToken for AsyncDeviceRxToken<'_, N> {
    fn consume<R, F>(mut self, f: F) -> R
    where
        F: FnOnce(&[u8]) -> R,
    {
        let ret = f(&mut self.grant);
        self.grant.release();
        self.sem.notify();
        trace!("AsyncDevice: added one rx permit");
        ret
    }
}

/// The transmit token for an [`AsyncDevice`].
pub struct AsyncDeviceTxToken<'a, const N: usize> {
    grant: FrameGrantW<'a, N>,
    sem: &'a NotificationSender,
}

impl<const N: usize> phy::TxToken for AsyncDeviceTxToken<'_, N> {
    fn consume<R, F>(mut self, len: usize, f: F) -> R
    where
        F: FnOnce(&mut [u8]) -> R,
    {
        // note: smoltcp expects the buffer to be sized *exactly*
        let ret = f(&mut self.grant[..len]);
        self.grant.commit(len);
        self.sem.notify();
        trace!("AsyncDevice: added one tx permit");
        ret
    }
}

impl<const N: usize> phy::Device for AsyncDevice<N> {
    // ooh fancy GATs
    type RxToken<'a> = AsyncDeviceRxToken<'a, N>;
    type TxToken<'a> = AsyncDeviceTxToken<'a, N>;

    fn receive(&mut self, _: Instant) -> Option<(Self::RxToken<'_>, Self::TxToken<'_>)> {
        if let Some(rx_grant) = self.rx_buffer.read() {
            if let Ok(tx_grant) = self.tx_buffer.grant(self.mtu) {
                return Some((
                    AsyncDeviceRxToken {
                        grant: rx_grant,
                        sem: &self.sem,
                    },
                    AsyncDeviceTxToken {
                        grant: tx_grant,
                        sem: &self.sem,
                    },
                ));
            }
        }
        None
    }

    fn transmit(&mut self, _: Instant) -> Option<Self::TxToken<'_>> {
        if let Ok(grant) = self.tx_buffer.grant(self.mtu) {
            Some(AsyncDeviceTxToken {
                grant,
                sem: &self.sem,
            })
        } else {
            None
        }
    }

    fn capabilities(&self) -> DeviceCapabilities {
        let mut capabs = DeviceCapabilities::default();
        capabs.max_transmission_unit = self.mtu;
        capabs.medium = self.medium;
        capabs
    }
}
