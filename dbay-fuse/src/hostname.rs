//! Hostname resolution helper shared by uplink_worker (conflict sidecar
//! naming) and pull (conflict-pull sidecar naming). On macOS, `$HOSTNAME`
//! isn't exported by default — so we fall back to the `hostname` shell command.

pub fn hostname_or_unknown() -> String {
    std::env::var("HOSTNAME").ok()
        .or_else(|| {
            std::process::Command::new("hostname").output().ok()
                .and_then(|o| String::from_utf8(o.stdout).ok())
                .map(|s| s.trim().to_string())
        })
        .filter(|s| !s.is_empty())
        .unwrap_or_else(|| "unknown".into())
}
