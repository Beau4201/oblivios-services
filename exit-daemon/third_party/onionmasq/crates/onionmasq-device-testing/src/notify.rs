//! Primitives for notifying an async task.
//!
//! This is similar to [`tokio::sync::Notify`], but less of a pain to use.
//! (Alternative options would also include just passing wakers around, but, again, pain to use.)
//!
//! Other options would also include just using channels. Maybe I should've done that.
//! (But that kind of feels like a hack, since they're semantically different.)

use std::sync::Arc;
use std::task::{Context, Poll};
use tokio::sync::Semaphore;
use tokio_util::sync::PollSemaphore;

/// A notification sender, used to wake up a corresponding receiver.
#[derive(Clone)]
pub(crate) struct NotificationSender {
    inner: Arc<Semaphore>,
}

impl NotificationSender {
    /// Make a new sender/receiver pair. Calling `notify()` on the sender will wake up the receiver.
    pub(crate) fn new_pair() -> (Self, NotificationReceiver) {
        let sem = Arc::new(Semaphore::new(0));
        (
            Self {
                inner: Arc::clone(&sem),
            },
            NotificationReceiver {
                inner: PollSemaphore::new(sem),
            },
        )
    }

    /// Wake up the corresponding receiver.
    pub(crate) fn notify(&self) {
        self.inner.add_permits(1);
    }
}

/// A notification receiver, receiving notifications from its paired [`NotificationSender`].
pub(crate) struct NotificationReceiver {
    inner: PollSemaphore,
}

impl NotificationReceiver {
    /// Check if any notifications were sent since this was last called, and return `true` if so.
    /// Return `Err(())` if all senders have been dropped.
    ///
    /// This schedules the async task calling it to be woken up when notifications are issued.
    pub(crate) fn check_for_notifications(&mut self, cx: &mut Context<'_>) -> Result<bool, ()> {
        let mut got_permits = false;
        loop {
            match self.inner.poll_acquire(cx) {
                // Got a permit! Retry the loop to see if there are any more.
                Poll::Ready(Some(permit)) => {
                    got_permits = true;
                    permit.forget();
                }
                // The semaphore was closed; error out.
                Poll::Ready(None) => {
                    return Err(());
                }
                // We've registered a wakeup, so we'll yield.
                Poll::Pending => break,
            }
        }
        Ok(got_permits)
    }
}
