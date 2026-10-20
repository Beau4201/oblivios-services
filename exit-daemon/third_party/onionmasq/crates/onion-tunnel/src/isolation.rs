//! Utilities for circuit isolation.

use arti_client::isolation::IsolationHelper;

/// A reimplementation of the arti `IsolationToken`.
#[derive(Clone, Copy, PartialEq, Eq, Debug, Hash)]
pub struct OnionIsolationKey {
    /// The app ID returned from the scaffolding.
    pub app_id: u64,
    /// A per-app epoch value, which we can increment in order to refresh an app's circuits.
    pub app_epoch: u16,
    /// A global epoch value, which we can increment in order to refresh all circuits.
    pub global_epoch: u16,
}

impl IsolationHelper for OnionIsolationKey {
    fn compatible_same_type(&self, other: &Self) -> bool {
        self == other
    }

    fn join_same_type(&self, other: &Self) -> Option<Self> {
        if other == self {
            Some(*self)
        } else {
            None
        }
    }
}
