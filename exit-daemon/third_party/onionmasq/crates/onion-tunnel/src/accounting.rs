//! Support for tracking bandwidth usage.

use std::sync::atomic::{AtomicU64, Ordering};

/// The singleton instance of `BandwidthCounter`.
static BANDWIDTH_COUNTER: BandwidthCounter = BandwidthCounter::new();

/// A singleton `struct` that tracks bytes sent and received by Arti over the network.
#[derive(Debug)]
pub struct BandwidthCounter {
    bytes_rx: AtomicU64,
    bytes_tx: AtomicU64,
}

impl BandwidthCounter {
    /// Create a new bandwidth counter.
    pub const fn new() -> Self {
        Self {
            bytes_rx: AtomicU64::new(0),
            bytes_tx: AtomicU64::new(0),
        }
    }

    /// Get the program-wide bandwidth counter.
    pub fn global() -> &'static Self {
        &BANDWIDTH_COUNTER
    }

    /// Reset the counter to zero.
    pub fn reset(&self) {
        self.bytes_rx.store(0, Ordering::SeqCst);
        self.bytes_tx.store(0, Ordering::SeqCst);
    }

    /// Get the current count of received bytes since last reset.
    pub fn bytes_rx(&self) -> u64 {
        self.bytes_rx.load(Ordering::Relaxed)
    }

    /// Get the current count of sent bytes since last reset.
    pub fn bytes_tx(&self) -> u64 {
        self.bytes_tx.load(Ordering::Relaxed)
    }

    /// Increment the number of received bytes.
    pub(crate) fn on_rx(&self, bytes: u64) {
        self.bytes_rx.fetch_add(bytes, Ordering::Relaxed);
    }

    /// Increment the number of sent bytes.
    pub(crate) fn on_tx(&self, bytes: u64) {
        self.bytes_tx.fetch_add(bytes, Ordering::Relaxed);
    }
}

impl Default for BandwidthCounter {
    fn default() -> Self {
        Self::new()
    }
}

impl PartialEq for BandwidthCounter {
    fn eq(&self, other: &Self) -> bool {
        self.bytes_rx() == other.bytes_rx() && self.bytes_tx() == other.bytes_tx()
    }
}

#[cfg(test)]
mod test {
    use super::*;

    #[test]
    fn test_bandwidth_counter() {
        assert_eq!(
            BANDWIDTH_COUNTER,
            BandwidthCounter {
                bytes_rx: AtomicU64::new(0),
                bytes_tx: AtomicU64::new(0)
            }
        );
        assert_eq!(BANDWIDTH_COUNTER, BandwidthCounter::new());
        assert_eq!(BandwidthCounter::global(), &BANDWIDTH_COUNTER);
        assert_eq!(BandwidthCounter::new(), BandwidthCounter::default());

        // Just for testing `PartialEq`'s `ne()`, otherwise redundant.
        assert!(!(BandwidthCounter::new() != BandwidthCounter::default()));

        BANDWIDTH_COUNTER.on_rx(42);
        BANDWIDTH_COUNTER.on_tx(142);
        assert_eq!(BANDWIDTH_COUNTER.bytes_rx(), 42);
        assert_eq!(BANDWIDTH_COUNTER.bytes_tx(), 142);

        BANDWIDTH_COUNTER.reset();
        assert_eq!(BANDWIDTH_COUNTER, BandwidthCounter::new());
    }
}
