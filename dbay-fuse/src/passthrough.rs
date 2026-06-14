//! Passthrough FUSE filesystem backed by a local `state_dir`.
//!
//! Writes go to two places:
//!   1. The local state file (direct POSIX write, durability on each op)
//!   2. The outbox — async upload queue to DBay LakebaseFS
//!
//! The outbox enqueue happens at "flush triggers":
//!   · release/FUSE flush (close) — queued to the background watchdog
//!   · fsync                      — synchronous, for explicit durability
//!   · watchdog idle-500ms / dirty-1MB — handled by flush_watchdog via
//!     this module's `flush_dirty()` entry point.
//!
//! Reads come straight from state_dir (no network on the read path).

use anyhow::{Context, Result};
use fuser::{
    FileAttr, FileType, Filesystem, MountOption, ReplyAttr, ReplyCreate, ReplyData,
    ReplyDirectory, ReplyEmpty, ReplyEntry, ReplyOpen, ReplyWrite, Request, TimeOrNow,
};
use libc::{EIO, ENOENT};
use std::collections::HashMap;
use std::ffi::OsStr;
use std::fs::{self, File, OpenOptions};
use std::io::{Read, Seek, SeekFrom, Write};
use std::os::unix::fs::{MetadataExt, OpenOptionsExt, PermissionsExt};
use std::path::{Path, PathBuf};
use std::sync::{mpsc, Arc};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use crate::append_state::{self, AppendMap, FlushMode};
use crate::flush_watchdog::{FlushCmd, FlushWatchdog};
use crate::outbox::{self, Op, Outbox};
use crate::profile::FolderProfile;

const TTL: Duration = Duration::from_secs(1);
const ROOT_INO: u64 = 1;

pub fn mount(
    agent: &str,
    mount_point: &Path,
    state_dir: &Path,
    outbox_dir: &Path,
    profile: FolderProfile,
) -> Result<()> {
    let outbox = Arc::new(Outbox::open(outbox_dir)?);
    let append_map = append_state::new_map();

    // Trigger a mount-start rescan so files written to state but not yet
    // flushed before a previous daemon exit are reconstructed into outbox ops.
    crate::state_scan::write_rescan_trigger(outbox_dir)?;

    // Start uplink worker (reads outbox, POSTs to LakebaseFS)
    crate::uplink_worker::spawn(
        agent,
        outbox.clone(),
        state_dir,
        outbox_dir,
        Some(profile.properties()),
    )?;

    // Start flush watchdog (idle + size triggers)
    let watchdog = FlushWatchdog::spawn();

    let fs = PassthroughFS::new(
        state_dir.to_path_buf(),
        outbox.clone(),
        watchdog.tx(),
        append_map.clone(),
        profile.properties(),
    );

    // Wire watchdog → flush callback (uses delta upload when possible)
    let state_clone = state_dir.to_path_buf();
    let outbox_clone = outbox.clone();
    let map_clone = append_map.clone();
    let properties_clone = profile.properties();
    watchdog.install_flush(move |path: PathBuf| {
        if let Err(e) = flush_path(&state_clone, &path, &outbox_clone, &map_clone, &properties_clone) {
            tracing::warn!(?e, ?path, "watchdog flush failed");
        }
    });

    let opts = vec![
        MountOption::FSName(format!("dbay-{agent}")),
        MountOption::AutoUnmount,
        MountOption::DefaultPermissions,
    ];
    tracing::info!(?mount_point, ?state_dir, "fuse mount starting");
    fuser::mount2(fs, mount_point, &opts)?;
    Ok(())
}

/// Flush a dirty path to outbox — delta append when possible, else full put.
/// Used by watchdog AND release handler. Emits per-step timing trace.
fn flush_path(
    state_dir: &Path,
    rel: &Path,
    outbox: &Outbox,
    append_map: &AppendMap,
    properties: &serde_json::Value,
) -> Result<()> {
    use std::time::Instant;
    let total = Instant::now();
    let real = state_dir.join(rel);
    let t = Instant::now();
    let meta = fs::symlink_metadata(&real).context("stat for flush")?;
    let t_stat = t.elapsed();
    if meta.is_dir() {
        return Ok(());
    }
    let virt_path = to_virt_path(rel);
    let t = Instant::now();
    let plan = append_state::plan_flush(append_map, rel);
    let t_plan = t.elapsed();
    match plan {
        FlushMode::Nothing => Ok(()),
        FlushMode::Append { from, to } if from > 0 => {
            use std::io::{Read, Seek, SeekFrom};
            let t = Instant::now();
            let mut f = fs::File::open(&real).context("open for append delta")?;
            f.seek(SeekFrom::Start(from))?;
            let mut delta = vec![0u8; (to - from) as usize];
            f.read_exact(&mut delta)?;
            let t_read = t.elapsed();
            let t = Instant::now();
            let sha = outbox::write_blob(&outbox.blobs_dir(), &delta)?;
            let t_blob = t.elapsed();
            let t = Instant::now();
            outbox.enqueue(Op::Append { path: virt_path, blob: sha })?;
            let t_enq = t.elapsed();
            append_state::mark_flushed(append_map, rel);
            tracing::info!(
                stat_us=t_stat.as_micros() as u64,
                plan_us=t_plan.as_micros() as u64,
                read_us=t_read.as_micros() as u64,
                blob_us=t_blob.as_micros() as u64,
                enq_us=t_enq.as_micros() as u64,
                total_us=total.elapsed().as_micros() as u64,
                "flush_path APPEND"
            );
            Ok(())
        }
        FlushMode::Append { .. } | FlushMode::Put => {
            let t = Instant::now();
            let data = fs::read(&real).context("read for put")?;
            let t_read = t.elapsed();
            let t = Instant::now();
            let sha = outbox::write_blob(&outbox.blobs_dir(), &data)?;
            let t_blob = t.elapsed();
            let t = Instant::now();
            outbox.enqueue(Op::Put {
                path: virt_path,
                blob: sha,
                properties: Some(properties.clone()),
            })?;
            let t_enq = t.elapsed();
            append_state::mark_flushed(append_map, rel);
            tracing::info!(
                stat_us=t_stat.as_micros() as u64,
                plan_us=t_plan.as_micros() as u64,
                read_us=t_read.as_micros() as u64,
                blob_us=t_blob.as_micros() as u64,
                enq_us=t_enq.as_micros() as u64,
                total_us=total.elapsed().as_micros() as u64,
                "flush_path PUT"
            );
            Ok(())
        }
    }
}

/// Convert a state-relative path (e.g. `memory/user.md`) to the virtual path
/// we send to LakebaseFS (e.g. `/memory/user.md`).
fn to_virt_path(rel: &Path) -> String {
    let s = rel.to_string_lossy();
    if s.starts_with('/') {
        s.into_owned()
    } else {
        format!("/{s}")
    }
}

fn relative_to_root(root: &Path, full: &Path) -> Option<PathBuf> {
    full.strip_prefix(root).ok().map(|p| p.to_path_buf())
}

struct PassthroughFS {
    root: PathBuf,
    outbox: Arc<Outbox>,
    flush_tx: mpsc::Sender<FlushCmd>,
    append_map: AppendMap,
    properties: serde_json::Value,

    inodes: HashMap<u64, PathBuf>,
    paths: HashMap<PathBuf, u64>,
    next_ino: u64,

    files: HashMap<u64, OpenFh>,
    next_fh: u64,
}

struct OpenFh {
    file: File,
    real_path: PathBuf,
    dirty: bool,
    /// Bytes written since last flush (for size-threshold trigger).
    dirty_bytes: u64,
    opened_at: Instant,
    opened_with_append: bool,
}

impl PassthroughFS {
    fn new(
        root: PathBuf,
        outbox: Arc<Outbox>,
        flush_tx: mpsc::Sender<FlushCmd>,
        append_map: AppendMap,
        properties: serde_json::Value,
    ) -> Self {
        let mut inodes = HashMap::new();
        let mut paths = HashMap::new();
        inodes.insert(ROOT_INO, root.clone());
        paths.insert(root.clone(), ROOT_INO);
        Self {
            root,
            outbox,
            flush_tx,
            append_map,
            properties,
            inodes,
            paths,
            next_ino: 2,
            files: HashMap::new(),
            next_fh: 1,
        }
    }

    fn resolve(&self, ino: u64) -> Option<PathBuf> {
        self.inodes.get(&ino).cloned()
    }

    fn intern(&mut self, path: PathBuf) -> u64 {
        if let Some(&ino) = self.paths.get(&path) {
            return ino;
        }
        let ino = self.next_ino;
        self.next_ino += 1;
        self.inodes.insert(ino, path.clone());
        self.paths.insert(path, ino);
        ino
    }

    fn forget_path(&mut self, path: &Path) {
        if let Some(ino) = self.paths.remove(path) {
            self.inodes.remove(&ino);
        }
    }

    fn enqueue_delete(&self, real: &Path) {
        if let Some(rel) = relative_to_root(&self.root, real) {
            let virt = to_virt_path(&rel);
            if let Err(e) = self.outbox.enqueue(Op::Delete { path: virt }) {
                tracing::warn!(?e, "enqueue delete failed");
            }
        }
    }

    fn enqueue_rmdir(&self, real: &Path) {
        if let Some(rel) = relative_to_root(&self.root, real) {
            let virt = to_virt_path(&rel);
            if let Err(e) = self.outbox.enqueue(Op::Delete { path: virt }) {
                tracing::warn!(?e, "enqueue rmdir failed");
            }
        }
    }

    fn enqueue_rename(&self, src: &Path, dst: &Path) {
        let rel_src = relative_to_root(&self.root, src);
        let rel_dst = relative_to_root(&self.root, dst);
        if let (Some(s), Some(d)) = (rel_src, rel_dst) {
            let _ = self.outbox.enqueue(Op::Rename {
                path: to_virt_path(&s),
                new_path: to_virt_path(&d),
            });
        }
    }

    fn enqueue_mkdir(&self, real: &Path) {
        if let Some(rel) = relative_to_root(&self.root, real) {
            let _ = self.outbox.enqueue(Op::Mkdir {
                path: to_virt_path(&rel),
                properties: Some(self.properties.clone()),
            });
        }
    }

    fn flush_fh(&mut self, fh: u64) -> Result<()> {
        let Some(f) = self.files.get_mut(&fh) else { return Ok(()); };
        // Always check append_state — even if this fd wasn't dirtied directly
        // (e.g. setattr-truncate from a separate setattr op), the file may
        // have pending changes that need to flush before release tells the
        // watchdog to drop tracking.
        if let Some(rel) = relative_to_root(&self.root, &f.real_path) {
            flush_path(&self.root, &rel, &self.outbox, &self.append_map, &self.properties)?;
        }
        f.dirty = false;
        f.dirty_bytes = 0;
        Ok(())
    }
}

fn attr_from_meta(ino: u64, meta: &fs::Metadata) -> FileAttr {
    let kind = if meta.is_dir() {
        FileType::Directory
    } else if meta.file_type().is_symlink() {
        FileType::Symlink
    } else {
        FileType::RegularFile
    };
    let mtime = meta.modified().unwrap_or(UNIX_EPOCH);
    let atime = meta.accessed().unwrap_or(mtime);
    let ctime = mtime;
    FileAttr {
        ino,
        size: meta.len(),
        blocks: (meta.len() + 511) / 512,
        atime,
        mtime,
        ctime,
        crtime: ctime,
        kind,
        perm: (meta.permissions().mode() & 0o7777) as u16,
        nlink: meta.nlink() as u32,
        uid: meta.uid(),
        gid: meta.gid(),
        rdev: meta.rdev() as u32,
        blksize: 4096,
        flags: 0,
    }
}

impl Filesystem for PassthroughFS {
    fn lookup(&mut self, _req: &Request, parent: u64, name: &OsStr, reply: ReplyEntry) {
        let Some(parent_path) = self.resolve(parent) else { return reply.error(ENOENT); };
        let path = parent_path.join(name);
        match fs::symlink_metadata(&path) {
            Ok(meta) => {
                let ino = self.intern(path.clone());
                reply.entry(&TTL, &attr_from_meta(ino, &meta), 0);
            }
            Err(_) => reply.error(ENOENT),
        }
    }

    fn getattr(&mut self, _req: &Request, ino: u64, reply: ReplyAttr) {
        let Some(path) = self.resolve(ino) else { return reply.error(ENOENT); };
        match fs::symlink_metadata(&path) {
            Ok(meta) => reply.attr(&TTL, &attr_from_meta(ino, &meta)),
            Err(_) => reply.error(ENOENT),
        }
    }

    fn setattr(
        &mut self,
        _req: &Request,
        ino: u64,
        mode: Option<u32>,
        _uid: Option<u32>,
        _gid: Option<u32>,
        size: Option<u64>,
        _atime: Option<TimeOrNow>,
        _mtime: Option<TimeOrNow>,
        _ctime: Option<SystemTime>,
        _fh: Option<u64>,
        _crtime: Option<SystemTime>,
        _chgtime: Option<SystemTime>,
        _bkuptime: Option<SystemTime>,
        _flags: Option<u32>,
        reply: ReplyAttr,
    ) {
        let Some(path) = self.resolve(ino) else { return reply.error(ENOENT); };
        if let Some(m) = mode {
            let _ = fs::set_permissions(&path, fs::Permissions::from_mode(m));
        }
        if let Some(new_size) = size {
            if let Ok(f) = OpenOptions::new().write(true).open(&path) {
                let _ = f.set_len(new_size);
            }
            if let Some(rel) = relative_to_root(&self.root, &path) {
                append_state::note_truncate(&self.append_map, &rel, new_size);
                let _ = self.flush_tx.send(FlushCmd::Wrote {
                    path: rel,
                    bytes: new_size,
                });
            }
        }
        match fs::symlink_metadata(&path) {
            Ok(meta) => reply.attr(&TTL, &attr_from_meta(ino, &meta)),
            Err(_) => reply.error(EIO),
        }
    }

    fn readdir(
        &mut self,
        _req: &Request,
        ino: u64,
        _fh: u64,
        offset: i64,
        mut reply: ReplyDirectory,
    ) {
        let Some(path) = self.resolve(ino) else { return reply.error(ENOENT); };
        let Ok(entries) = fs::read_dir(&path) else { return reply.error(EIO); };
        let mut all: Vec<(String, FileType, u64)> = Vec::new();
        all.push((".".into(), FileType::Directory, ino));
        all.push(("..".into(), FileType::Directory, ino));
        for e in entries.flatten() {
            let name = e.file_name().to_string_lossy().into_owned();
            let full = path.join(&name);
            let meta = match e.metadata() {
                Ok(m) => m,
                Err(_) => continue,
            };
            let kind = if meta.is_dir() {
                FileType::Directory
            } else if meta.file_type().is_symlink() {
                FileType::Symlink
            } else {
                FileType::RegularFile
            };
            let child_ino = self.intern(full);
            all.push((name, kind, child_ino));
        }
        for (i, (name, kind, cino)) in all.into_iter().enumerate().skip(offset as usize) {
            if reply.add(cino, (i + 1) as i64, kind, name) {
                break;
            }
        }
        reply.ok();
    }

    fn open(&mut self, _req: &Request, ino: u64, flags: i32, reply: ReplyOpen) {
        let Some(path) = self.resolve(ino) else { return reply.error(ENOENT); };
        let mut opts = OpenOptions::new();
        let acc = flags & libc::O_ACCMODE;
        opts.read(acc == libc::O_RDONLY || acc == libc::O_RDWR);
        opts.write(acc == libc::O_WRONLY || acc == libc::O_RDWR);
        if flags & libc::O_APPEND != 0 {
            opts.append(true);
        }
        opts.custom_flags(flags);
        match opts.open(&path) {
            Ok(f) => {
                // Seed / refresh append state. If O_TRUNC was used, file was just
                // truncated to 0 — invalidate pure_append AND mark dirty so that
                // a subsequent close (even with no write) still triggers flush.
                let truncated = flags & libc::O_TRUNC != 0;
                if let Some(rel) = relative_to_root(&self.root, &path) {
                    let size = f.metadata().map(|m| m.len()).unwrap_or(0);
                    if truncated {
                        append_state::note_truncate(&self.append_map, &rel, size);
                    } else {
                        append_state::ensure_entry(&self.append_map, &rel, size);
                    }
                }
                let fh = self.next_fh;
                self.next_fh += 1;
                self.files.insert(fh, OpenFh {
                    file: f,
                    real_path: path,
                    dirty: truncated,
                    dirty_bytes: 0,
                    opened_at: Instant::now(),
                    opened_with_append: flags & libc::O_APPEND != 0,
                });
                reply.opened(fh, 0);
            }
            Err(e) => reply.error(e.raw_os_error().unwrap_or(EIO)),
        }
    }

    fn read(
        &mut self,
        _req: &Request,
        _ino: u64,
        fh: u64,
        offset: i64,
        size: u32,
        _flags: i32,
        _lock_owner: Option<u64>,
        reply: ReplyData,
    ) {
        let Some(f) = self.files.get_mut(&fh) else { return reply.error(EIO); };
        if f.file.seek(SeekFrom::Start(offset as u64)).is_err() {
            return reply.error(EIO);
        }
        let mut buf = vec![0u8; size as usize];
        match f.file.read(&mut buf) {
            Ok(n) => {
                buf.truncate(n);
                reply.data(&buf);
            }
            Err(e) => reply.error(e.raw_os_error().unwrap_or(EIO)),
        }
    }

    fn write(
        &mut self,
        _req: &Request,
        _ino: u64,
        fh: u64,
        offset: i64,
        data: &[u8],
        _write_flags: u32,
        flags: i32,
        _lock_owner: Option<u64>,
        reply: ReplyWrite,
    ) {
        let Some(f) = self.files.get_mut(&fh) else { return reply.error(EIO); };
        let is_append_mode = flags & libc::O_APPEND != 0 || f.opened_with_append;
        // For O_APPEND opens macFUSE may pass offset=0; honor real append by
        // seeking to End instead.
        let seek_target = if is_append_mode {
            SeekFrom::End(0)
        } else {
            SeekFrom::Start(offset as u64)
        };
        if f.file.seek(seek_target).is_err() {
            return reply.error(EIO);
        }
        let rel = relative_to_root(&self.root, &f.real_path);
        match f.file.write(data) {
            Ok(n) => {
                f.dirty = true;
                f.dirty_bytes += n as u64;
                if let Some(r) = &rel {
                    // Read actual file size after write — robust against
                    // O_APPEND / sparse / kernel-vs-FUSE offset quirks.
                    let new_size = f.file.metadata().map(|m| m.len()).unwrap_or(0);
                    append_state::note_write_to_size(&self.append_map, r, new_size, is_append_mode);
                }
                // notify watchdog
                if let Some(r) = rel {
                    let _ = self.flush_tx.send(FlushCmd::Wrote {
                        path: r,
                        bytes: f.dirty_bytes,
                    });
                }
                reply.written(n as u32);
            }
            Err(e) => reply.error(e.raw_os_error().unwrap_or(EIO)),
        }
    }

    fn flush(
        &mut self,
        _req: &Request,
        _ino: u64,
        fh: u64,
        _lock_owner: u64,
        reply: ReplyEmpty,
    ) {
        if let Some(f) = self.files.get(&fh) {
            if let Some(rel) = relative_to_root(&self.root, &f.real_path) {
                let _ = self.flush_tx.send(FlushCmd::Closed { path: rel });
            }
        }
        reply.ok();
    }

    fn fsync(
        &mut self,
        _req: &Request,
        _ino: u64,
        fh: u64,
        _datasync: bool,
        reply: ReplyEmpty,
    ) {
        if let Some(f) = self.files.get_mut(&fh) {
            let _ = f.file.sync_all();
        }
        if let Err(e) = self.flush_fh(fh) {
            tracing::warn!(?e, "fsync enqueue failed");
        }
        reply.ok();
    }

    fn release(
        &mut self,
        _req: &Request,
        _ino: u64,
        fh: u64,
        _flags: i32,
        _lock_owner: Option<u64>,
        _flush: bool,
        reply: ReplyEmpty,
    ) {
        if let Some(f) = self.files.remove(&fh) {
            if let Some(rel) = relative_to_root(&self.root, &f.real_path) {
                let _ = self.flush_tx.send(FlushCmd::Closed { path: rel });
            }
        }
        reply.ok();
    }

    fn create(
        &mut self,
        _req: &Request,
        parent: u64,
        name: &OsStr,
        mode: u32,
        _umask: u32,
        flags: i32,
        reply: ReplyCreate,
    ) {
        let Some(parent_path) = self.resolve(parent) else { return reply.error(ENOENT); };
        let path = parent_path.join(name);
        let mut opts = OpenOptions::new();
        opts.read(true).write(true).create(true).truncate(flags & libc::O_TRUNC != 0);
        opts.mode(mode);
        opts.custom_flags(flags);
        match opts.open(&path) {
            Ok(f) => {
                let ino = self.intern(path.clone());
                let meta = match f.metadata() {
                    Ok(m) => m,
                    Err(_) => return reply.error(EIO),
                };
                if let Some(rel) = relative_to_root(&self.root, &path) {
                    append_state::ensure_entry(&self.append_map, &rel, 0);
                }
                let fh = self.next_fh;
                self.next_fh += 1;
                self.files.insert(fh, OpenFh {
                    file: f,
                    real_path: path,
                    dirty: true,  // fresh file counts as dirty so release → flush
                    dirty_bytes: 0,
                    opened_at: Instant::now(),
                    opened_with_append: flags & libc::O_APPEND != 0,
                });
                reply.created(&TTL, &attr_from_meta(ino, &meta), 0, fh, 0);
            }
            Err(e) => reply.error(e.raw_os_error().unwrap_or(EIO)),
        }
    }

    fn mkdir(
        &mut self,
        _req: &Request,
        parent: u64,
        name: &OsStr,
        mode: u32,
        _umask: u32,
        reply: ReplyEntry,
    ) {
        let Some(parent_path) = self.resolve(parent) else { return reply.error(ENOENT); };
        let path = parent_path.join(name);
        if let Err(e) = fs::create_dir(&path) {
            return reply.error(e.raw_os_error().unwrap_or(EIO));
        }
        let _ = fs::set_permissions(&path, fs::Permissions::from_mode(mode));
        self.enqueue_mkdir(&path);
        let ino = self.intern(path.clone());
        match fs::symlink_metadata(&path) {
            Ok(meta) => reply.entry(&TTL, &attr_from_meta(ino, &meta), 0),
            Err(_) => reply.error(EIO),
        }
    }

    fn unlink(&mut self, _req: &Request, parent: u64, name: &OsStr, reply: ReplyEmpty) {
        let Some(parent_path) = self.resolve(parent) else { return reply.error(ENOENT); };
        let path = parent_path.join(name);
        match fs::remove_file(&path) {
            Ok(_) => {
                self.enqueue_delete(&path);
                if let Some(rel) = relative_to_root(&self.root, &path) {
                    append_state::forget(&self.append_map, &rel);
                }
                self.forget_path(&path);
                reply.ok();
            }
            Err(e) => reply.error(e.raw_os_error().unwrap_or(EIO)),
        }
    }

    fn rmdir(&mut self, _req: &Request, parent: u64, name: &OsStr, reply: ReplyEmpty) {
        let Some(parent_path) = self.resolve(parent) else { return reply.error(ENOENT); };
        let path = parent_path.join(name);
        match fs::remove_dir(&path) {
            Ok(_) => {
                self.enqueue_rmdir(&path);
                self.forget_path(&path);
                reply.ok();
            }
            Err(e) => reply.error(e.raw_os_error().unwrap_or(EIO)),
        }
    }

    fn rename(
        &mut self,
        _req: &Request,
        parent: u64,
        name: &OsStr,
        newparent: u64,
        newname: &OsStr,
        _flags: u32,
        reply: ReplyEmpty,
    ) {
        let Some(parent_path) = self.resolve(parent) else { return reply.error(ENOENT); };
        let Some(newparent_path) = self.resolve(newparent) else { return reply.error(ENOENT); };
        let src = parent_path.join(name);
        let dst = newparent_path.join(newname);
        match fs::rename(&src, &dst) {
            Ok(_) => {
                self.enqueue_rename(&src, &dst);
                let rel_src = relative_to_root(&self.root, &src);
                let rel_dst = relative_to_root(&self.root, &dst);
                if let (Some(s), Some(d)) = (rel_src, rel_dst) {
                    append_state::rename(&self.append_map, &s, &d);
                }
                self.forget_path(&src);
                reply.ok();
            }
            Err(e) => reply.error(e.raw_os_error().unwrap_or(EIO)),
        }
    }
}
