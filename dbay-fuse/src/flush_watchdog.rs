//! Flush watchdog: idle + size-based trigger for flushing dirty files.
//!
//! The FUSE main thread only flushes synchronously on fsync. Normal FUSE
//! flush/close is queued here so user-visible writes are not blocked by
//! outbox fsync. The watchdog handles:
//!   · idle 500ms no-write   → flush
//!   · dirty_bytes > 1 MB    → flush
//!   · close / FUSE flush     → immediate background flush
//!
//! Design: one `std::thread`, one `mpsc::channel`. Uses `recv_timeout(200ms)`
//! so every ~200ms the watchdog wakes up to scan for idle dirty files.
//! no tokio. If it misses an idle trigger due to jitter, close still queues
//! an immediate background flush.
//!
//! Thread safety: the watchdog owns its own state (HashMap<path, last_write>).
//! It calls a user-provided `flush_callback(path)` which must be `Send + 'static`.
//! Implementations reopen the file from `state_dir` to read the bytes — it does
//! NOT reach back into the FUSE main thread.

use std::collections::HashMap;
use std::path::PathBuf;
use std::sync::mpsc::{self, RecvTimeoutError, Sender};
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::{Duration, Instant};

const TICK: Duration = Duration::from_millis(200);
const IDLE_MS: u64 = 500;
const SIZE_THRESHOLD: u64 = 1024 * 1024; // 1 MB dirty → flush

pub enum FlushCmd {
    Wrote { path: PathBuf, bytes: u64 },
    Closed { path: PathBuf },
    Shutdown,
}

pub struct FlushWatchdog {
    tx: Sender<FlushCmd>,
    flush_cb: Arc<Mutex<Option<Box<dyn Fn(PathBuf) + Send + 'static>>>>,
}

impl FlushWatchdog {
    pub fn spawn() -> Self {
        let (tx, rx) = mpsc::channel::<FlushCmd>();
        let flush_cb: Arc<Mutex<Option<Box<dyn Fn(PathBuf) + Send + 'static>>>> =
            Arc::new(Mutex::new(None));
        let cb_for_thread = flush_cb.clone();

        thread::spawn(move || {
            let mut state: HashMap<PathBuf, (Instant, u64)> = HashMap::new();
            loop {
                match rx.recv_timeout(TICK) {
                    Ok(FlushCmd::Wrote { path, bytes }) => {
                        let entry = state
                            .entry(path.clone())
                            .or_insert((Instant::now(), 0));
                        entry.0 = Instant::now();
                        entry.1 = bytes;
                        // Size trigger
                        if bytes >= SIZE_THRESHOLD {
                            if let Some(cb) = cb_for_thread.lock().unwrap().as_ref() {
                                cb(path.clone());
                            }
                            state.remove(&path);
                        }
                    }
                    Ok(FlushCmd::Closed { path }) => {
                        if let Some(cb) = cb_for_thread.lock().unwrap().as_ref() {
                            cb(path.clone());
                        }
                        state.remove(&path);
                    }
                    Ok(FlushCmd::Shutdown) => break,
                    Err(RecvTimeoutError::Timeout) => {
                        let now = Instant::now();
                        let due: Vec<PathBuf> = state
                            .iter()
                            .filter_map(|(p, (at, _))| {
                                if now.duration_since(*at).as_millis() >= IDLE_MS as u128 {
                                    Some(p.clone())
                                } else {
                                    None
                                }
                            })
                            .collect();
                        for p in due {
                            if let Some(cb) = cb_for_thread.lock().unwrap().as_ref() {
                                cb(p.clone());
                            }
                            state.remove(&p);
                        }
                    }
                    Err(RecvTimeoutError::Disconnected) => break,
                }
            }
            tracing::info!("flush watchdog shutdown");
        });

        Self { tx, flush_cb }
    }

    pub fn tx(&self) -> Sender<FlushCmd> {
        self.tx.clone()
    }

    /// Provide the function that actually performs the flush for a given
    /// relative path (relative to state_dir).
    pub fn install_flush<F>(&self, f: F)
    where
        F: Fn(PathBuf) + Send + 'static,
    {
        *self.flush_cb.lock().unwrap() = Some(Box::new(f));
    }
}

#[cfg(test)]
mod tests {
    use super::{FlushCmd, FlushWatchdog};
    use std::path::PathBuf;
    use std::sync::mpsc;
    use std::time::Duration;

    #[test]
    fn closed_triggers_background_flush_callback() {
        let watchdog = FlushWatchdog::spawn();
        let (tx, rx) = mpsc::channel();
        watchdog.install_flush(move |path| {
            tx.send(path).unwrap();
        });

        let path = PathBuf::from("notes/a.md");
        watchdog.tx().send(FlushCmd::Closed { path: path.clone() }).unwrap();

        assert_eq!(rx.recv_timeout(Duration::from_secs(2)).unwrap(), path);
    }
}
