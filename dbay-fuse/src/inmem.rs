//! In-memory FUSE backend (experimental, opt-in via `--in-memory`).
//!
//! Replaces the disk-passthrough + outbox + async-uplink model with a
//! per-inode in-memory buffer that is uploaded synchronously on flush.
//!
//! Properties:
//!   - read: lazy GET on first access; subsequent reads are served from buffer
//!   - write: append/splice into buffer; mark dirty (not yet committed)
//!   - flush / release: if dirty, single PUT with `If-Match: <prev_etag>`
//!     (or `If-None-Match: *` for fresh creates) — one HTTP req = one DB tx
//!   - daemon crashes before flush ⇒ no PUT was ever sent ⇒ server unchanged
//!     ("no flush = no commit"). The DB rollback is implicit because nothing
//!     was committed in the first place.
//!
//! Out of scope for v1:
//!   - rename/setxattr/symlink (return ENOSYS)
//!   - mid-stream truncate to non-zero (only `setattr size=0` and full close-with-empty-buffer work)
//!   - files larger than `MAX_BUFFER` (return EFBIG)
//!
//! Concurrency: fuser invokes Filesystem methods on a single thread, so we
//! keep state behind `&mut self` without an explicit Mutex. The DbayClient's
//! `reqwest::blocking::Client` blocks the FUSE thread on each HTTP call,
//! which is acceptable for the workloads we care about (CC writing small
//! JSON / JSONL files, ≤ a few MB).

use anyhow::{anyhow, Context, Result};
use fuser::{
    FileAttr, FileType, Filesystem, MountOption, ReplyAttr, ReplyCreate, ReplyData,
    ReplyDirectory, ReplyEmpty, ReplyEntry, ReplyOpen, ReplyWrite, Request, TimeOrNow,
};
use libc::{EEXIST, EFBIG, EIO, ENOENT, ENOSYS, ENOTDIR};
use std::collections::HashMap;
use std::ffi::{OsStr, OsString};
use std::path::Path;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use crate::dbay_api::{LakebaseFSEntry, LakebaseFSPutError, DbayClient};

const TTL: Duration = Duration::from_secs(1);
const MAX_BUFFER: usize = 64 * 1024 * 1024; // 64 MB per file
const PUT_RETRIES: u32 = 3;
const PUT_BACKOFF_MS: u64 = 200;

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
enum Kind {
    File,
    Dir,
}

struct Inode {
    path: String,           // virtual path, e.g. "/" or "/tasks/foo.json"
    kind: Kind,
    parent: u64,            // 0 for root
    children: HashMap<OsString, u64>,
    children_loaded: bool,  // true once we've called list() for this dir
    size: u64,
    mtime_ns: u64,
    /// Server's etag for the canonical content. None for: brand-new files
    /// not yet PUT, or directories (etag is per-file).
    etag: Option<String>,
    /// Lazy-loaded content. None = not loaded yet (buffer-on-demand).
    /// Some(empty) for fresh creates; Some(loaded bytes) once read.
    buffer: Option<Vec<u8>>,
    dirty: bool,
    open_count: u32,
}

impl Inode {
    fn new_dir(path: String, parent: u64, etag: Option<String>) -> Self {
        Self {
            path, kind: Kind::Dir, parent,
            children: HashMap::new(), children_loaded: false,
            size: 0, mtime_ns: now_ns(), etag,
            buffer: None, dirty: false, open_count: 0,
        }
    }
    fn new_file(path: String, parent: u64, size: u64, mtime_ns: u64, etag: String) -> Self {
        Self {
            path, kind: Kind::File, parent,
            children: HashMap::new(), children_loaded: false,
            size, mtime_ns, etag: Some(etag),
            buffer: None, dirty: false, open_count: 0,
        }
    }
    fn new_fresh_file(path: String, parent: u64) -> Self {
        Self {
            path, kind: Kind::File, parent,
            children: HashMap::new(), children_loaded: false,
            size: 0, mtime_ns: now_ns(), etag: None,
            buffer: Some(Vec::new()), dirty: true, open_count: 0,
        }
    }
}

pub struct InmemFs {
    client: DbayClient,
    inodes: HashMap<u64, Inode>,
    by_path: HashMap<String, u64>,
    next_ino: u64,
    next_fh: u64,
    /// fh -> ino (for fast lookup in read/write/flush/release)
    fh_to_ino: HashMap<u64, u64>,
}

impl InmemFs {
    fn new(client: DbayClient) -> Self {
        let mut s = Self {
            client,
            inodes: HashMap::new(),
            by_path: HashMap::new(),
            next_ino: 2, // 1 reserved for root
            next_fh: 1,
            fh_to_ino: HashMap::new(),
        };
        // Root inode
        s.inodes.insert(1, Inode::new_dir("/".to_string(), 0, None));
        s.by_path.insert("/".to_string(), 1);
        s
    }

    fn alloc_ino(&mut self) -> u64 {
        let n = self.next_ino;
        self.next_ino += 1;
        n
    }

    fn alloc_fh(&mut self) -> u64 {
        let n = self.next_fh;
        self.next_fh += 1;
        n
    }

    /// Find existing inode by path or insert a new one.
    fn intern(&mut self, parent_ino: u64, name: &OsStr, entry: &LakebaseFSEntry) -> u64 {
        let path = entry.path.clone();
        if let Some(&ino) = self.by_path.get(&path) {
            return ino;
        }
        let ino = self.alloc_ino();
        let inode = if entry.kind == "dir" {
            Inode::new_dir(path.clone(), parent_ino, Some(entry.etag.clone()))
        } else {
            Inode::new_file(path.clone(), parent_ino, entry.size, entry.mtime_ns, entry.etag.clone())
        };
        self.inodes.insert(ino, inode);
        self.by_path.insert(path, ino);
        if let Some(p) = self.inodes.get_mut(&parent_ino) {
            p.children.insert(name.to_owned(), ino);
        }
        ino
    }

    fn ensure_children_loaded(&mut self, ino: u64) -> Result<()> {
        let dir_path = match self.inodes.get(&ino) {
            Some(i) if i.kind == Kind::Dir && !i.children_loaded => i.path.clone(),
            _ => return Ok(()),
        };
        let entries = self.client.lbfs_list(&dir_path, false)
            .with_context(|| format!("list {dir_path}"))?;
        for e in &entries {
            if e.path == dir_path { continue; } // server may include the dir itself
            // Compute name from path
            let name = e.path.rsplit('/').next().unwrap_or("").to_string();
            if name.is_empty() { continue; }
            self.intern(ino, OsStr::new(&name), e);
        }
        if let Some(d) = self.inodes.get_mut(&ino) {
            d.children_loaded = true;
        }
        Ok(())
    }

    /// Load a file's content into its buffer if not yet loaded.
    fn ensure_buffer_loaded(&mut self, ino: u64) -> Result<()> {
        let path = match self.inodes.get(&ino) {
            Some(i) if i.kind == Kind::File && i.buffer.is_none() => i.path.clone(),
            _ => return Ok(()),
        };
        let (bytes, etag, mtime_ns) = self.client.lbfs_get(&path)
            .with_context(|| format!("get {path}"))?;
        if let Some(i) = self.inodes.get_mut(&ino) {
            i.size = bytes.len() as u64;
            i.mtime_ns = mtime_ns;
            i.etag = Some(etag);
            i.buffer = Some(bytes);
        }
        Ok(())
    }

    /// PUT the in-memory buffer to LakebaseFS with retries. Returns Err(EIO) on
    /// hard failure or precondition mismatch (concurrent modification).
    fn flush_inode(&mut self, ino: u64) -> std::result::Result<(), i32> {
        let (path, data, if_match, if_none_match) = {
            let i = self.inodes.get(&ino).ok_or(EIO)?;
            if i.kind != Kind::File || !i.dirty {
                return Ok(());
            }
            let buf = i.buffer.clone().unwrap_or_default();
            let if_match = i.etag.clone();
            let if_none_match = if i.etag.is_none() { Some("*".to_string()) } else { None };
            (i.path.clone(), buf, if_match, if_none_match)
        };

        let mut last_err: Option<LakebaseFSPutError> = None;
        for attempt in 0..PUT_RETRIES {
            match self.client.lbfs_put_strict(
                &path,
                &data,
                if_match.as_deref(),
                if_none_match.as_deref(),
            ) {
                Ok(new_etag) => {
                    if let Some(i) = self.inodes.get_mut(&ino) {
                        i.dirty = false;
                        i.etag = Some(new_etag);
                        i.size = data.len() as u64;
                        i.mtime_ns = now_ns();
                    }
                    return Ok(());
                }
                Err(LakebaseFSPutError::PreconditionFailed) => {
                    tracing::warn!(%path, "PUT precondition failed (concurrent modification?)");
                    return Err(EIO);
                }
                Err(other) => {
                    tracing::warn!(%path, attempt, %other, "PUT failed; retrying");
                    last_err = Some(other);
                    std::thread::sleep(Duration::from_millis(PUT_BACKOFF_MS << attempt));
                }
            }
        }
        tracing::error!(%path, ?last_err, "PUT failed after {PUT_RETRIES} retries");
        Err(EIO)
    }

    fn attr_for(&self, ino: u64) -> Option<FileAttr> {
        let i = self.inodes.get(&ino)?;
        let kind = match i.kind { Kind::File => FileType::RegularFile, Kind::Dir => FileType::Directory };
        let mtime = ns_to_systime(i.mtime_ns);
        Some(FileAttr {
            ino,
            size: i.size,
            blocks: (i.size + 511) / 512,
            atime: mtime,
            mtime,
            ctime: mtime,
            crtime: mtime,
            kind,
            perm: if i.kind == Kind::Dir { 0o755 } else { 0o644 },
            nlink: if i.kind == Kind::Dir { 2 } else { 1 },
            uid: unsafe { libc::getuid() },
            gid: unsafe { libc::getgid() },
            rdev: 0,
            blksize: 4096,
            flags: 0,
        })
    }
}

fn join_path(parent: &str, name: &str) -> String {
    if parent == "/" { format!("/{name}") } else { format!("{parent}/{name}") }
}

fn now_ns() -> u64 {
    SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_nanos() as u64).unwrap_or(0)
}

fn ns_to_systime(ns: u64) -> SystemTime {
    UNIX_EPOCH + Duration::from_nanos(ns)
}

impl Filesystem for InmemFs {
    fn lookup(&mut self, _req: &Request, parent: u64, name: &OsStr, reply: ReplyEntry) {
        let parent_path = match self.inodes.get(&parent) {
            Some(p) if p.kind == Kind::Dir => p.path.clone(),
            Some(_) => return reply.error(ENOTDIR),
            None => return reply.error(ENOENT),
        };
        // Fast path: child already in our cache
        if let Some(&ino) = self.inodes.get(&parent).and_then(|p| p.children.get(name)) {
            if let Some(attr) = self.attr_for(ino) {
                return reply.entry(&TTL, &attr, 0);
            }
        }
        let name_str = match name.to_str() { Some(s) => s, None => return reply.error(ENOENT) };
        let child_path = join_path(&parent_path, name_str);
        match self.client.lbfs_head(&child_path) {
            Ok(Some(entry)) => {
                let ino = self.intern(parent, name, &entry);
                if let Some(attr) = self.attr_for(ino) {
                    reply.entry(&TTL, &attr, 0);
                } else {
                    reply.error(EIO);
                }
            }
            Ok(None) => reply.error(ENOENT),
            Err(e) => {
                tracing::warn!(%child_path, ?e, "lookup failed");
                reply.error(EIO);
            }
        }
    }

    fn getattr(&mut self, _req: &Request, ino: u64, reply: ReplyAttr) {
        match self.attr_for(ino) {
            Some(attr) => reply.attr(&TTL, &attr),
            None => reply.error(ENOENT),
        }
    }

    fn open(&mut self, _req: &Request, ino: u64, flags: i32, reply: ReplyOpen) {
        let exists = self.inodes.contains_key(&ino);
        if !exists { return reply.error(ENOENT); }
        if flags & libc::O_TRUNC != 0 {
            if let Some(i) = self.inodes.get_mut(&ino) {
                if i.kind == Kind::File {
                    i.buffer = Some(Vec::new());
                    i.size = 0;
                    i.dirty = true;
                }
            }
        } else if let Err(_) = self.ensure_buffer_loaded(ino) {
            return reply.error(EIO);
        }
        if let Some(i) = self.inodes.get_mut(&ino) {
            i.open_count += 1;
        }
        let fh = self.alloc_fh();
        self.fh_to_ino.insert(fh, ino);
        reply.opened(fh, 0);
    }

    fn read(&mut self, _req: &Request, _ino: u64, fh: u64, offset: i64, size: u32,
            _flags: i32, _lock_owner: Option<u64>, reply: ReplyData) {
        let Some(&ino) = self.fh_to_ino.get(&fh) else { return reply.error(EIO); };
        if let Err(_) = self.ensure_buffer_loaded(ino) { return reply.error(EIO); }
        let Some(i) = self.inodes.get(&ino) else { return reply.error(ENOENT); };
        let buf = i.buffer.as_ref().unwrap();
        let start = (offset as usize).min(buf.len());
        let end = (start + size as usize).min(buf.len());
        reply.data(&buf[start..end]);
    }

    fn write(&mut self, _req: &Request, _ino: u64, fh: u64, offset: i64, data: &[u8],
             _write_flags: u32, flags: i32, _lock_owner: Option<u64>, reply: ReplyWrite) {
        let Some(&ino) = self.fh_to_ino.get(&fh) else { return reply.error(EIO); };
        // Need buffer loaded for offset writes (otherwise we'd lose existing content)
        if let Err(_) = self.ensure_buffer_loaded(ino) { return reply.error(EIO); }
        let Some(i) = self.inodes.get_mut(&ino) else { return reply.error(ENOENT); };
        let buf = i.buffer.get_or_insert_with(Vec::new);

        let is_append = flags & libc::O_APPEND != 0;
        let pos = if is_append { buf.len() } else { offset as usize };
        let need = pos + data.len();
        if need > MAX_BUFFER {
            tracing::warn!(path=%i.path, need, "write would exceed MAX_BUFFER");
            return reply.error(EFBIG);
        }
        if need > buf.len() { buf.resize(need, 0); }
        buf[pos..pos + data.len()].copy_from_slice(data);
        i.size = buf.len() as u64;
        i.mtime_ns = now_ns();
        i.dirty = true;
        reply.written(data.len() as u32);
    }

    fn flush(&mut self, _req: &Request, _ino: u64, fh: u64, _lock_owner: u64, reply: ReplyEmpty) {
        let Some(&ino) = self.fh_to_ino.get(&fh) else { return reply.error(EIO); };
        match self.flush_inode(ino) {
            Ok(()) => reply.ok(),
            Err(errno) => reply.error(errno),
        }
    }

    fn fsync(&mut self, _req: &Request, _ino: u64, fh: u64, _datasync: bool, reply: ReplyEmpty) {
        let Some(&ino) = self.fh_to_ino.get(&fh) else { return reply.error(EIO); };
        match self.flush_inode(ino) {
            Ok(()) => reply.ok(),
            Err(errno) => reply.error(errno),
        }
    }

    fn release(&mut self, _req: &Request, _ino: u64, fh: u64, _flags: i32,
               _lock_owner: Option<u64>, _flush_flag: bool, reply: ReplyEmpty) {
        let Some(&ino) = self.fh_to_ino.remove(&fh).as_ref() else { return reply.error(EIO); };
        // Best-effort flush — release without prior flush() should still commit
        // (close() is the commit boundary in our model). Errors on release are
        // logged but not surfaced to userspace (can't unwind close()).
        if let Err(errno) = self.flush_inode(ino) {
            tracing::warn!(ino, errno, "release: flush_inode failed");
        }
        if let Some(i) = self.inodes.get_mut(&ino) {
            if i.open_count > 0 { i.open_count -= 1; }
            // Drop the buffer if no more readers/writers and we're clean — saves memory.
            if i.open_count == 0 && !i.dirty {
                i.buffer = None;
            }
        }
        reply.ok();
    }

    fn create(&mut self, _req: &Request, parent: u64, name: &OsStr, _mode: u32,
              _umask: u32, _flags: i32, reply: ReplyCreate) {
        let parent_path = match self.inodes.get(&parent) {
            Some(p) if p.kind == Kind::Dir => p.path.clone(),
            Some(_) => return reply.error(ENOTDIR),
            None => return reply.error(ENOENT),
        };
        let name_str = match name.to_str() { Some(s) => s, None => return reply.error(EIO) };
        let path = join_path(&parent_path, name_str);
        // Don't pre-PUT — defer to flush. Just track the inode.
        if let Some(p) = self.inodes.get(&parent) {
            if p.children.contains_key(name) { return reply.error(EEXIST); }
        }
        let ino = self.alloc_ino();
        self.inodes.insert(ino, Inode::new_fresh_file(path.clone(), parent));
        self.by_path.insert(path, ino);
        if let Some(p) = self.inodes.get_mut(&parent) {
            p.children.insert(name.to_owned(), ino);
        }
        let fh = self.alloc_fh();
        self.fh_to_ino.insert(fh, ino);
        if let Some(i) = self.inodes.get_mut(&ino) {
            i.open_count += 1;
        }
        let attr = self.attr_for(ino).unwrap();
        reply.created(&TTL, &attr, 0, fh, 0);
    }

    fn mkdir(&mut self, _req: &Request, parent: u64, name: &OsStr, _mode: u32,
             _umask: u32, reply: ReplyEntry) {
        let parent_path = match self.inodes.get(&parent) {
            Some(p) if p.kind == Kind::Dir => p.path.clone(),
            Some(_) => return reply.error(ENOTDIR),
            None => return reply.error(ENOENT),
        };
        let name_str = match name.to_str() { Some(s) => s, None => return reply.error(EIO) };
        let path = join_path(&parent_path, name_str);
        if let Err(e) = self.client.lbfs_mkdir(&path, None) {
            tracing::warn!(%path, ?e, "mkdir server failed");
            return reply.error(EIO);
        }
        let ino = self.alloc_ino();
        self.inodes.insert(ino, Inode::new_dir(path.clone(), parent, None));
        self.by_path.insert(path, ino);
        if let Some(p) = self.inodes.get_mut(&parent) {
            p.children.insert(name.to_owned(), ino);
        }
        if let Some(attr) = self.attr_for(ino) {
            reply.entry(&TTL, &attr, 0);
        } else {
            reply.error(EIO);
        }
    }

    fn unlink(&mut self, _req: &Request, parent: u64, name: &OsStr, reply: ReplyEmpty) {
        let (child_ino, child_path, etag) = {
            let p = match self.inodes.get(&parent) {
                Some(p) if p.kind == Kind::Dir => p,
                _ => return reply.error(ENOENT),
            };
            let &ino = match p.children.get(name) {
                Some(i) => i,
                None => return reply.error(ENOENT),
            };
            let i = self.inodes.get(&ino).unwrap();
            (ino, i.path.clone(), i.etag.clone())
        };
        if let Err(e) = self.client.lbfs_delete_strict(&child_path, etag.as_deref()) {
            tracing::warn!(%child_path, ?e, "delete server failed");
            return reply.error(EIO);
        }
        if let Some(p) = self.inodes.get_mut(&parent) { p.children.remove(name); }
        self.inodes.remove(&child_ino);
        self.by_path.remove(&child_path);
        reply.ok();
    }

    fn rmdir(&mut self, req: &Request, parent: u64, name: &OsStr, reply: ReplyEmpty) {
        // Treat same as unlink for now; server enforces non-empty check if any.
        self.unlink(req, parent, name, reply)
    }

    fn readdir(&mut self, _req: &Request, ino: u64, _fh: u64, offset: i64, mut reply: ReplyDirectory) {
        if let Err(e) = self.ensure_children_loaded(ino) {
            tracing::warn!(?e, ino, "readdir list failed");
            return reply.error(EIO);
        }
        let Some(dir) = self.inodes.get(&ino) else { return reply.error(ENOENT); };
        if dir.kind != Kind::Dir { return reply.error(ENOTDIR); }
        let mut all: Vec<(String, FileType, u64)> = Vec::new();
        all.push((".".to_string(), FileType::Directory, ino));
        all.push(("..".to_string(), FileType::Directory, dir.parent.max(1)));
        for (name, &cino) in &dir.children {
            let kind = match self.inodes.get(&cino).map(|c| c.kind) {
                Some(Kind::Dir) => FileType::Directory,
                _ => FileType::RegularFile,
            };
            all.push((name.to_string_lossy().into_owned(), kind, cino));
        }
        for (i, (name, kind, child_ino)) in all.into_iter().enumerate().skip(offset as usize) {
            if reply.add(child_ino, (i + 1) as i64, kind, name) { break; }
        }
        reply.ok();
    }

    fn setattr(&mut self, _req: &Request, ino: u64, _mode: Option<u32>, _uid: Option<u32>,
               _gid: Option<u32>, size: Option<u64>, _atime: Option<TimeOrNow>,
               _mtime: Option<TimeOrNow>, _ctime: Option<SystemTime>, _fh: Option<u64>,
               _crtime: Option<SystemTime>, _chgtime: Option<SystemTime>,
               _bkuptime: Option<SystemTime>, _flags: Option<u32>, reply: ReplyAttr) {
        // Only handle truncate-to-0 (`O_TRUNC` post-open via setattr, or `truncate(0)`).
        if let Some(0) = size {
            if let Some(i) = self.inodes.get_mut(&ino) {
                if i.kind == Kind::File {
                    i.buffer = Some(Vec::new());
                    i.size = 0;
                    i.dirty = true;
                }
            }
        } else if size.is_some() {
            // Non-zero truncate not supported in v1.
            return reply.error(ENOSYS);
        }
        if let Some(attr) = self.attr_for(ino) { reply.attr(&TTL, &attr); } else { reply.error(ENOENT); }
    }
}

pub fn mount(agent: &str, mount_point: &Path) -> Result<()> {
    let client = DbayClient::for_agent(agent)?
        .ok_or_else(|| anyhow!("DBay not configured for agent {agent}; run `dbay login` first"))?;
    tracing::info!(agent, base_id=%client.base_id(), "in-memory FUSE backend starting");
    let fs = InmemFs::new(client);
    let opts = vec![
        MountOption::FSName(format!("dbay-inmem-{agent}")),
        MountOption::AutoUnmount,
        MountOption::DefaultPermissions,
    ];
    fuser::mount2(fs, mount_point, &opts).context("fuser mount2")?;
    Ok(())
}
