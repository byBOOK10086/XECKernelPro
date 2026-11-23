#![deny(clippy::all, clippy::pedantic)]
#![warn(clippy::nursery)]

// Compile the production Unix filesystem code on Linux, even though ksud's
// Android-only module is excluded from its host boot-image patcher binary.
#[path = "../../userspace/ksud/src/private_bootlog.rs"]
pub mod private_bootlog;
