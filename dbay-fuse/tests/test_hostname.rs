use dbay_fuse::hostname::hostname_or_unknown;

#[test]
fn hostname_returns_non_empty_string() {
    let h = hostname_or_unknown();
    assert!(!h.is_empty(), "hostname helper returned empty string");
}

#[test]
fn hostname_uses_env_var_when_set() {
    // This test is order-dependent — it only verifies the env path WHEN HOSTNAME is set.
    // On macOS HOSTNAME isn't exported by default, so we set it ourselves.
    let prev = std::env::var("HOSTNAME").ok();
    std::env::set_var("HOSTNAME", "test-host-marker-xyz");
    let h = hostname_or_unknown();
    // Restore previous state immediately to avoid bleed
    match prev {
        Some(v) => std::env::set_var("HOSTNAME", v),
        None => std::env::remove_var("HOSTNAME"),
    }
    assert_eq!(h, "test-host-marker-xyz");
}
