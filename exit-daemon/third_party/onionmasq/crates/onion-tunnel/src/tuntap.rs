//! A basic async TUN/TAP device that uses a file descriptor.
//!
//! (This is more of a generic anything device than it is a TUN/TAP device, though :p)

use libc::{c_int, c_void};
#[cfg(any(target_os = "linux", target_os = "android"))]
use smoltcp::phy::{Medium, TunTapInterface};
use std::io::Error;
use std::os::unix::io::{AsRawFd, RawFd};
use std::pin::Pin;
use std::task::{Context, Poll};
use std::{io, mem};
use tokio::io::unix::AsyncFd;
use tokio::io::{AsyncRead, AsyncWrite, ReadBuf};

/// An asynchronous TUN/TAP interface, implementing `AsyncRead` and `AsyncWrite`.
pub struct AsyncTunTapInterface {
    inner: AsyncFd<RawFd>,
}

impl Drop for AsyncTunTapInterface {
    fn drop(&mut self) {
        unsafe {
            let _ = libc::close(self.inner.as_raw_fd());
        }
    }
}

impl AsyncTunTapInterface {
    /// Attaches to a TUN/TAP interface called `name`, or creates it if it does not exist.
    ///
    /// If `name` is a persistent interface configured with UID of the current user,
    /// no special privileges are needed. Otherwise, this requires superuser privileges
    /// or a corresponding capability set on the executable.
    ///
    /// (This calls out to smoltcp's `TunTapInterface`).
    #[cfg(any(target_os = "linux", target_os = "android"))]
    pub fn new(iface_name: &str, medium: Medium) -> io::Result<Self> {
        let stcp = TunTapInterface::new(iface_name, medium)?;
        Self::new_from_fd(stcp)
    }

    /// Wrap a file descriptor, which is assumed to be a fully configured TUN/TAP device.
    ///
    /// # Ownership
    ///
    /// The device will take ownership of the file descriptor, and call `close()` on it
    /// when it is itself closed.
    ///
    /// The destructor of `inner` will NOT be run ([`mem::forget`] will be called).
    ///
    /// # Warning
    ///
    /// The file descriptor will be made non-blocking with ioctl().
    /// No other checks are performed on the file descriptor!
    pub fn new_from_fd<F: AsRawFd>(inner: F) -> io::Result<Self> {
        let fd = inner.as_raw_fd();
        // Make sure the thing wrapping the fd before doesn't attempt to close it on drop.
        mem::forget(inner);

        let ret = unsafe {
            let value: c_int = 1;
            libc::ioctl(fd, libc::FIONBIO, &value)
        };
        if ret < 0 {
            return Err(io::Error::last_os_error());
        }
        Ok(Self {
            inner: AsyncFd::new(fd)?,
        })
    }

    fn read(&self, buf: &mut [u8]) -> io::Result<usize> {
        let fd = self.inner.as_raw_fd();
        let ret = unsafe { libc::read(fd, buf.as_mut_ptr() as *mut c_void, buf.len()) };
        if ret < 0 {
            return Err(io::Error::last_os_error());
        }
        Ok(ret as usize)
    }

    fn write(&self, buf: &[u8]) -> io::Result<usize> {
        let fd = self.inner.as_raw_fd();
        let ret = unsafe { libc::write(fd, buf.as_ptr() as *const c_void, buf.len()) };
        if ret < 0 {
            return Err(io::Error::last_os_error());
        }
        Ok(ret as usize)
    }

    fn flush(&self) -> io::Result<()> {
        let fd = self.inner.as_raw_fd();

        let ret = unsafe { libc::fsync(fd) };
        if ret < 0 {
            return Err(io::Error::last_os_error());
        }
        Ok(())
    }
}

impl AsRawFd for AsyncTunTapInterface {
    fn as_raw_fd(&self) -> RawFd {
        self.inner.as_raw_fd()
    }
}

impl AsyncRead for AsyncTunTapInterface {
    fn poll_read(
        self: Pin<&mut Self>,
        cx: &mut Context<'_>,
        buf: &mut ReadBuf<'_>,
    ) -> Poll<io::Result<()>> {
        let this = self.as_ref();
        loop {
            match this.inner.poll_read_ready(cx) {
                Poll::Ready(Ok(mut guard)) => {
                    match guard.try_io(|_| this.read(buf.initialize_unfilled())) {
                        Ok(Ok(n)) => {
                            buf.advance(n);
                            return Poll::Ready(Ok(()));
                        }
                        Ok(Err(e)) => return Poll::Ready(Err(e)),
                        Err(_) => {
                            // Huh, that's weird. Despite the `poll_read_ready` saying we could
                            // read, we got a WouldBlock error.
                            // Retry the poll, I guess.
                            continue;
                        }
                    }
                }
                Poll::Ready(Err(e)) => return Poll::Ready(Err(e)),
                Poll::Pending => return Poll::Pending,
            }
        }
    }
}

impl AsyncWrite for AsyncTunTapInterface {
    fn poll_write(
        self: Pin<&mut Self>,
        cx: &mut Context<'_>,
        buf: &[u8],
    ) -> Poll<Result<usize, Error>> {
        // NOTE(eta): A lot of this is tasty copypasta from the poll_read impl.

        let this = self.as_ref();
        loop {
            match this.inner.poll_write_ready(cx) {
                Poll::Ready(Ok(mut guard)) => {
                    match guard.try_io(|_| this.write(buf)) {
                        Ok(Ok(n)) => {
                            return Poll::Ready(Ok(n));
                        }
                        Ok(Err(e)) => return Poll::Ready(Err(e)),
                        Err(_) => {
                            // Huh, that's weird. Despite the `poll_write_ready` saying we could
                            // read, we got a WouldBlock error.
                            // Retry the poll, I guess.
                            continue;
                        }
                    }
                }
                Poll::Ready(Err(e)) => return Poll::Ready(Err(e)),
                Poll::Pending => return Poll::Pending,
            }
        }
    }

    fn poll_flush(self: Pin<&mut Self>, _: &mut Context<'_>) -> Poll<Result<(), Error>> {
        Poll::Ready(self.as_ref().flush())
    }

    fn poll_shutdown(self: Pin<&mut Self>, _: &mut Context<'_>) -> Poll<Result<(), Error>> {
        Poll::Ready(Ok(()))
    }
}
