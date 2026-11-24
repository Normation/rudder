//! TOCTOU-safe wrappers for common filesystem operations.
//!
//! Paths are resolved one component at a time with `openat(2)`, always
//! relative to a file descriptor of the previously validated directory, so a
//! component cannot be swapped between the check and the use.
//!
//! # Security model
//!
//! An object is trusted when it is owned by root or by the effective UID.
//!
//! The following rules are always enforced:
//!
//! - Symlinks are resolved by this module rather than by the kernel. Each
//!   component is opened with `O_NOFOLLOW`; when it is a symlink, its target
//!   is read with `readlinkat(2)` and the link is followed only if:
//!   - by default (like CFEngine), the symlink is trusted, or it has the same
//!     owner and the same group as its target. A file is never created
//!     through an untrusted dangling symlink, as the new file would be owned
//!     by the effective user;
//!   - with `strict_owner`, the symlink itself is trusted, whoever owns its
//!     target.
//!
//!   At most [`MAX_SYMLINKS`] symlinks are followed.
//! - The final file must be a regular file.
//! - New files are created with `O_CREAT | O_EXCL`.
//!
//! # Strict mode
//!
//! With the `strict_owner` option, the following checks are added:
//!
//! - Every traversed directory must be trusted and must not be writable by
//!   group or others, unless it has the sticky bit (e.g. `/tmp`).
//! - The final file, including a newly created one, must be trusted, must not
//!   be writable by group or others, and must not have other hard links.
//! - For relative paths, the current working directory is validated, but not
//!   its ancestors.
//!
//! # Examples
//!
//! Open an existing file read-only (the default):
//!
//! ```no_run
//! use std::io::Read;
//! use rudder_module_type::safefs::SafeOpenOptions;
//!
//! let mut content = String::new();
//! SafeOpenOptions::new().open("/etc/hostname")?.read_to_string(&mut content)?;
//! # Ok::<(), Box<dyn std::error::Error>>(())
//! ```
//!
//! Open read-write, creating the file with mode `0o640` if it is missing:
//!
//! ```no_run
//! use std::io::Write;
//! use rudder_module_type::safefs::SafeOpenOptions;
//!
//! let mut file = SafeOpenOptions::new()
//!     .write(true)
//!     .truncate(true)
//!     .create(true)
//!     .mode(0o640)
//!     .open("/var/rudder/tmp/state.json")?;
//! file.write_all(b"{}")?;
//! # Ok::<(), Box<dyn std::error::Error>>(())
//! ```
//!
//! Handle a rejected path:
//!
//! ```no_run
//! use rudder_module_type::safefs::{SafeFsError, SafeOpenOptions};
//!
//! match SafeOpenOptions::new().strict_owner(true).open("/tmp/some/file") {
//!     Ok(_file) => { /* use the file */ }
//!     Err(SafeFsError::SymLinkNotOwned { path, owner, .. }) => {
//!         eprintln!("refusing untrusted symlink {} (uid {owner})", path.display());
//!     }
//!     Err(SafeFsError::WritableByOthers { path, .. }) => {
//!         eprintln!("{} can be modified by other users", path.display());
//!     }
//!     Err(e) => eprintln!("cannot open file: {e}"),
//! }
//! ```

#![cfg(target_os = "linux")]

use std::{
    collections::VecDeque,
    ffi::{OsStr, OsString},
    fmt::{self, Display},
    fs::{File, Permissions},
    os::{
        fd::OwnedFd,
        unix::{
            ffi::{OsStrExt, OsStringExt},
            fs::PermissionsExt,
        },
    },
    path::{Component, Path, PathBuf},
};

use rustix::{
    fs::{CWD, FileType, Mode, OFlags, Stat, fstat, ftruncate, openat, readlinkat},
    io::Errno,
    process::{fchdir, getegid, geteuid},
};

/// Same limit as the Linux kernel (`MAXSYMLINKS`).
pub const MAX_SYMLINKS: u32 = 40;

const S_ISVTX: u32 = 0o1000;
const WRITABLE_BY_OTHERS: u32 = 0o022;

const PATH_FLAGS: OFlags = OFlags::PATH.union(OFlags::NOFOLLOW).union(OFlags::CLOEXEC);
const DIR_FLAGS: OFlags = PATH_FLAGS.union(OFlags::DIRECTORY);

#[derive(Debug)]
pub enum SafeFsError {
    SymLinkNotOwned {
        path: PathBuf,
        owner: u32,
        euid: u32,
    },
    SymLinkOwnerMismatch {
        path: PathBuf,
        link_owner: u32,
        link_group: u32,
        target_owner: u32,
        target_group: u32,
    },
    NotOwned {
        path: PathBuf,
        owner: u32,
        euid: u32,
    },
    WritableByOthers {
        path: PathBuf,
        mode: u32,
    },
    HardLinked {
        path: PathBuf,
        nlink: u64,
    },
    TooManySymlinks(PathBuf),
    PathHasNoFilename(PathBuf),
    NotFile(PathBuf),
    NotDirectory(PathBuf),
    InvalidPathComponent(PathBuf),
    Raced(PathBuf),
    Io(std::io::Error),
}

impl Display for SafeFsError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            SafeFsError::SymLinkNotOwned { path, owner, euid } => write!(
                f,
                "symlink '{}' is owned by uid {owner}, expected root (0) or the effective user (uid {euid})",
                path.display()
            ),
            SafeFsError::SymLinkOwnerMismatch {
                path,
                link_owner,
                link_group,
                target_owner,
                target_group,
            } => write!(
                f,
                "symlink '{}' (uid {link_owner}, gid {link_group}) does not have the same owner and group as its target (uid {target_owner}, gid {target_group})",
                path.display()
            ),
            SafeFsError::NotOwned { path, owner, euid } => write!(
                f,
                "'{}' is owned by uid {owner}, expected root (0) or the effective user (uid {euid})",
                path.display()
            ),
            SafeFsError::WritableByOthers { path, mode } => write!(
                f,
                "'{}' is writable by group or others (mode {:o})",
                path.display(),
                mode & 0o7777
            ),
            SafeFsError::HardLinked { path, nlink } => {
                write!(f, "'{}' has {nlink} hard links, expected 1", path.display())
            }
            SafeFsError::TooManySymlinks(path) => write!(
                f,
                "too many levels of symbolic links (>{MAX_SYMLINKS}) resolving '{}'",
                path.display()
            ),
            SafeFsError::PathHasNoFilename(path) => {
                write!(f, "path '{}' has no filename", path.display())
            }
            SafeFsError::NotFile(path) => {
                write!(f, "'{}' exists and is not a regular file", path.display())
            }
            SafeFsError::NotDirectory(path) => {
                write!(f, "'{}' is not a directory", path.display())
            }
            SafeFsError::InvalidPathComponent(path) => {
                write!(f, "invalid path component in '{}'", path.display())
            }
            SafeFsError::Raced(path) => write!(
                f,
                "'{}' was modified concurrently while being opened",
                path.display()
            ),
            SafeFsError::Io(err) => write!(f, "{err}"),
        }
    }
}

impl std::error::Error for SafeFsError {
    fn source(&self) -> Option<&(dyn std::error::Error + 'static)> {
        match self {
            SafeFsError::Io(err) => std::error::Error::source(err),
            _ => None,
        }
    }
}

impl From<std::io::Error> for SafeFsError {
    fn from(err: std::io::Error) -> Self {
        SafeFsError::Io(err)
    }
}

impl From<Errno> for SafeFsError {
    fn from(err: Errno) -> Self {
        SafeFsError::Io(err.into())
    }
}

/// Builder for opening a file safely, modeled after [`std::fs::OpenOptions`].
///
/// Configure it with the chained setters, then call [`open`](Self::open).
/// Defaults: read-only, no creation, mode `0o600` for created files.
///
/// Differences from [`std::fs::OpenOptions`]:
///
/// * `read` defaults to `true`, so [`new`](Self::new) alone opens the file
///   read-only.
/// * disabling both `read` and `write` does not fail, the file is opened
///   read-only.
///
/// # Example
///
/// ```
/// # use std::os::unix::fs::PermissionsExt;
/// use std::io::{Read, Write};
/// use rudder_module_type::safefs::SafeOpenOptions;
///
/// # let dir = tempfile::tempdir()?;
/// # std::fs::set_permissions(dir.path(), std::fs::Permissions::from_mode(0o700))?;
/// let path = dir.path().join("config");
///
/// // Create the file and write to it.
/// let mut file = SafeOpenOptions::new()
///     .write(true)
///     .create(true)
///     .mode(0o600)
///     .open(&path)?;
/// file.write_all(b"hello")?;
///
/// // Re-open it read-only.
/// let mut content = String::new();
/// SafeOpenOptions::new().open(&path)?.read_to_string(&mut content)?;
/// assert_eq!(content, "hello");
/// # Ok::<(), Box<dyn std::error::Error>>(())
/// ```
#[derive(Debug, Clone)]
pub struct SafeOpenOptions {
    read: bool,
    write: bool,
    truncate: bool,
    create: bool,
    mode: u32,
    strict_owner: bool,
}

impl Default for SafeOpenOptions {
    fn default() -> Self {
        Self {
            read: true,
            write: false,
            truncate: false,
            create: false,
            mode: 0o600,
            strict_owner: false,
        }
    }
}

impl SafeOpenOptions {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn read(&mut self, read: bool) -> &mut Self {
        self.read = read;
        self
    }

    pub fn write(&mut self, write: bool) -> &mut Self {
        self.write = write;
        self
    }

    pub fn truncate(&mut self, truncate: bool) -> &mut Self {
        self.truncate = truncate;
        self
    }

    pub fn create(&mut self, create: bool) -> &mut Self {
        self.create = create;
        self
    }

    pub fn mode(&mut self, mode: u32) -> &mut Self {
        self.mode = mode & 0o777;
        self
    }

    pub fn strict_owner(&mut self, strict_owner: bool) -> &mut Self {
        self.strict_owner = strict_owner;
        self
    }

    pub fn open<P: AsRef<Path>>(&self, path: P) -> Result<File, SafeFsError> {
        if self.truncate && !self.write {
            return Err(SafeFsError::Io(std::io::Error::new(
                std::io::ErrorKind::InvalidInput,
                "truncate requires write access",
            )));
        }
        walk(path.as_ref(), self, WalkTarget::File).map(File::from)
    }

    fn access_flags(&self) -> OFlags {
        match (self.read, self.write) {
            (true, true) => OFlags::RDWR,
            (false, true) => OFlags::WRONLY,
            _ => OFlags::RDONLY,
        }
    }
}

/// Open `path` read-write, creating it with `perms` if it does not exist.
///
/// Shortcut for
/// `SafeOpenOptions::new().read(true).write(true).create(true).mode(perms.mode())`.
///
/// # Example
///
/// ```
/// use std::fs::Permissions;
/// use std::io::Write;
/// use std::os::unix::fs::PermissionsExt;
/// use rudder_module_type::safefs::safe_open;
///
/// # let dir = tempfile::tempdir()?;
/// # std::fs::set_permissions(dir.path(), Permissions::from_mode(0o700))?;
/// let mut file = safe_open(dir.path().join("state"), &Permissions::from_mode(0o600))?;
/// file.write_all(b"ok")?;
/// # Ok::<(), Box<dyn std::error::Error>>(())
/// ```
pub fn safe_open<P: AsRef<Path>>(path: P, perms: &Permissions) -> Result<File, SafeFsError> {
    SafeOpenOptions::new()
        .read(true)
        .write(true)
        .create(true)
        .mode(perms.mode())
        .open(path)
}

enum Step {
    Name(OsString),
    Parent,
    CheckLink { link: PathBuf, ids: (u32, u32) },
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum WalkTarget {
    Dir,
    File,
}

fn ids(st: &Stat) -> (u32, u32) {
    (st.st_uid, st.st_gid)
}

fn trusted(st: &Stat, euid: u32) -> bool {
    st.st_uid == 0 || st.st_uid == euid
}

fn check_link(
    link: &Path,
    link_ids: (u32, u32),
    target_ids: (u32, u32),
) -> Result<(), SafeFsError> {
    if link_ids != target_ids {
        return Err(SafeFsError::SymLinkOwnerMismatch {
            path: link.to_owned(),
            link_owner: link_ids.0,
            link_group: link_ids.1,
            target_owner: target_ids.0,
            target_group: target_ids.1,
        });
    }
    Ok(())
}

fn check_pending_links(
    pending: &VecDeque<Step>,
    target_ids: (u32, u32),
) -> Result<(), SafeFsError> {
    for step in pending {
        if let Step::CheckLink { link, ids } = step {
            check_link(link, *ids, target_ids)?;
        }
    }
    Ok(())
}

fn check_strict(st: &Stat, path: &Path, euid: u32) -> Result<(), SafeFsError> {
    if !trusted(st, euid) {
        return Err(SafeFsError::NotOwned {
            path: path.to_owned(),
            owner: st.st_uid,
            euid,
        });
    }
    let is_dir = FileType::from_raw_mode(st.st_mode) == FileType::Directory;
    let sticky_dir = is_dir && st.st_mode & S_ISVTX != 0;
    if st.st_mode & WRITABLE_BY_OTHERS != 0 && !sticky_dir {
        return Err(SafeFsError::WritableByOthers {
            path: path.to_owned(),
            mode: st.st_mode,
        });
    }
    #[allow(clippy::useless_conversion)]
    let nlink = u64::from(st.st_nlink);
    if !is_dir && nlink > 1 {
        return Err(SafeFsError::HardLinked {
            path: path.to_owned(),
            nlink,
        });
    }
    Ok(())
}

fn open_start(start: &str, opts: &SafeOpenOptions, euid: u32) -> Result<OwnedFd, SafeFsError> {
    let fd = openat(CWD, start, DIR_FLAGS, Mode::empty())?;
    if opts.strict_owner {
        check_strict(&fstat(&fd)?, Path::new(start), euid)?;
    }
    Ok(fd)
}

fn reopen_file(
    dir: &OwnedFd,
    name: &OsStr,
    expected: &Stat,
    shown: &Path,
    opts: &SafeOpenOptions,
) -> Result<OwnedFd, SafeFsError> {
    let flags = opts.access_flags()
        | OFlags::NOFOLLOW
        | OFlags::CLOEXEC
        | OFlags::NOCTTY
        | OFlags::NONBLOCK;
    let fd = openat(dir, name, flags, Mode::empty())?;
    let st = fstat(&fd)?;
    if (st.st_dev, st.st_ino) != (expected.st_dev, expected.st_ino) {
        return Err(SafeFsError::Raced(shown.to_owned()));
    }
    if opts.truncate {
        ftruncate(&fd, 0)?;
    }
    Ok(fd)
}

fn create_file(
    dir: &OwnedFd,
    name: &OsStr,
    shown: &Path,
    opts: &SafeOpenOptions,
    euid: u32,
) -> Result<OwnedFd, SafeFsError> {
    let flags = opts.access_flags()
        | OFlags::CREATE
        | OFlags::EXCL
        | OFlags::NOFOLLOW
        | OFlags::CLOEXEC
        | OFlags::NOCTTY;
    let fd = match openat(dir, name, flags, Mode::from_raw_mode(opts.mode)) {
        Ok(fd) => fd,
        Err(Errno::EXIST) => return Err(SafeFsError::Raced(shown.to_owned())),
        Err(e) => return Err(e.into()),
    };
    if opts.strict_owner {
        check_strict(&fstat(&fd)?, shown, euid)?;
    }
    Ok(fd)
}

fn walk(path: &Path, opts: &SafeOpenOptions, target: WalkTarget) -> Result<OwnedFd, SafeFsError> {
    let mut pending = VecDeque::new();
    for comp in path.components() {
        match comp {
            Component::Normal(name) => pending.push_back(Step::Name(name.to_owned())),
            Component::CurDir | Component::RootDir => {}
            Component::ParentDir | Component::Prefix(_) => {
                return Err(SafeFsError::InvalidPathComponent(path.to_owned()));
            }
        }
    }

    if target == WalkTarget::File {
        if pending.is_empty() {
            return Err(SafeFsError::PathHasNoFilename(path.to_owned()));
        }
        if path.as_os_str().as_bytes().ends_with(b"/") {
            return Err(SafeFsError::InvalidPathComponent(path.to_owned()));
        }
    }

    let euid = geteuid().as_raw();
    let start = if path.has_root() { "/" } else { "." };
    let mut cur = open_start(start, opts, euid)?;
    let mut shown = PathBuf::from(start);
    let mut links = 0;

    while let Some(step) = pending.pop_front() {
        let name = match step {
            Step::CheckLink {
                link,
                ids: link_ids,
            } => {
                check_link(&link, link_ids, ids(&fstat(&cur)?))?;
                continue;
            }
            Step::Parent => {
                cur = openat(&cur, "..", DIR_FLAGS, Mode::empty())?;
                match shown.components().next_back() {
                    Some(Component::Normal(_)) => {
                        shown.pop();
                    }
                    _ => shown.push(".."),
                }
                if opts.strict_owner {
                    check_strict(&fstat(&cur)?, &shown, euid)?;
                }
                continue;
            }
            Step::Name(name) => name,
        };
        shown.push(&name);

        let last = !pending
            .iter()
            .any(|s| matches!(s, Step::Name(_) | Step::Parent));
        let want_file = last && target == WalkTarget::File;

        let fd = match openat(&cur, &name, PATH_FLAGS, Mode::empty()) {
            Ok(fd) => fd,
            Err(Errno::NOENT) if want_file && opts.create => {
                check_pending_links(&pending, (euid, getegid().as_raw()))?;
                return create_file(&cur, &name, &shown, opts, euid);
            }
            Err(e) => return Err(e.into()),
        };
        let st = fstat(&fd)?;

        match FileType::from_raw_mode(st.st_mode) {
            FileType::Symlink => {
                if !trusted(&st, euid) {
                    if opts.strict_owner {
                        return Err(SafeFsError::SymLinkNotOwned {
                            path: shown,
                            owner: st.st_uid,
                            euid,
                        });
                    }
                    pending.push_front(Step::CheckLink {
                        link: shown.clone(),
                        ids: ids(&st),
                    });
                }
                links += 1;
                if links > MAX_SYMLINKS {
                    return Err(SafeFsError::TooManySymlinks(path.to_owned()));
                }
                let link_target = readlinkat(&fd, "", Vec::new())?;
                let link_target = PathBuf::from(OsString::from_vec(link_target.into_bytes()));

                shown.pop();
                if link_target.has_root() {
                    cur = open_start("/", opts, euid)?;
                    shown = PathBuf::from("/");
                }
                for comp in link_target.components().rev() {
                    match comp {
                        Component::Normal(n) => pending.push_front(Step::Name(n.to_owned())),
                        Component::ParentDir => pending.push_front(Step::Parent),
                        _ => {}
                    }
                }
            }
            FileType::Directory if !want_file => {
                if opts.strict_owner {
                    check_strict(&st, &shown, euid)?;
                }
                cur = fd;
            }
            FileType::RegularFile if want_file => {
                if opts.strict_owner {
                    check_strict(&st, &shown, euid)?;
                }
                check_pending_links(&pending, ids(&st))?;
                return reopen_file(&cur, &name, &st, &shown, opts);
            }
            _ if want_file => return Err(SafeFsError::NotFile(shown)),
            _ => return Err(SafeFsError::NotDirectory(shown)),
        }
    }

    match target {
        WalkTarget::Dir => Ok(openat(
            &cur,
            ".",
            OFlags::RDONLY | OFlags::DIRECTORY | OFlags::CLOEXEC,
            Mode::empty(),
        )?),
        WalkTarget::File => Err(SafeFsError::NotFile(path.to_owned())),
    }
}

/// Change the current working directory.
///
/// # Example
///
/// ```
/// use std::fs::Permissions;
/// use std::os::unix::fs::PermissionsExt;
/// use rudder_module_type::safefs::{safe_chdir, SafeFsError};
///
/// # let dir = tempfile::tempdir()?;
/// # std::fs::set_permissions(dir.path(), Permissions::from_mode(0o700))?;
/// # std::fs::create_dir(dir.path().join("work"))?;
/// # std::fs::write(dir.path().join("file"), b"")?;
/// safe_chdir(dir.path().join("work"), false)?;
/// assert_eq!(
///     std::env::current_dir()?,
///     dir.path().join("work").canonicalize()?
/// );
/// # Ok::<(), Box<dyn std::error::Error>>(())
/// ```
pub fn safe_chdir<P: AsRef<Path>>(path: P, strict_owner: bool) -> Result<(), SafeFsError> {
    let fd = walk(
        path.as_ref(),
        SafeOpenOptions::new().strict_owner(strict_owner),
        WalkTarget::Dir,
    )?;
    fchdir(fd)?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    use std::fs::{self, File, Permissions};
    use std::io::{ErrorKind, Read, Write};
    use std::os::unix::fs::{MetadataExt, PermissionsExt};

    use tempfile::TempDir;

    fn tmp() -> TempDir {
        let tmp = tempfile::tempdir().unwrap();
        fs::set_permissions(tmp.path(), Permissions::from_mode(0o700)).unwrap();
        tmp
    }

    fn create_file(tmp: &TempDir, name: &str, mode: u32) -> PathBuf {
        let path = tmp.path().join(name);
        File::create(&path).unwrap().write_all(b"data").unwrap();
        fs::set_permissions(&path, Permissions::from_mode(mode)).unwrap();
        path
    }

    fn create_dir(tmp: &TempDir, name: &str, mode: u32) -> PathBuf {
        let path = tmp.path().join(name);
        fs::create_dir(&path).unwrap();
        fs::set_permissions(&path, Permissions::from_mode(mode)).unwrap();
        path
    }

    fn strict() -> SafeOpenOptions {
        let mut opts = SafeOpenOptions::new();
        opts.strict_owner(true);
        opts
    }

    #[test]
    fn opens_regular_file() {
        let tmp = tmp();
        let path = create_file(&tmp, "file", 0o600);
        let mut content = String::new();
        SafeOpenOptions::new()
            .open(&path)
            .unwrap()
            .read_to_string(&mut content)
            .unwrap();
        assert_eq!(content, "data");
    }

    #[test]
    fn missing_file_fails() {
        let tmp = tmp();
        let err = SafeOpenOptions::new()
            .open(tmp.path().join("missing"))
            .unwrap_err();
        assert!(matches!(err, SafeFsError::Io(e) if e.kind() == ErrorKind::NotFound));
    }

    #[test]
    fn creates_file_with_mode() {
        let tmp = tmp();
        let path = tmp.path().join("new");
        safe_open(&path, &Permissions::from_mode(0o600)).unwrap();
        assert_eq!(fs::metadata(&path).unwrap().mode() & 0o777, 0o600);
    }

    #[test]
    fn truncates_file() {
        let tmp = tmp();
        let path = create_file(&tmp, "file", 0o600);
        SafeOpenOptions::new()
            .write(true)
            .truncate(true)
            .open(&path)
            .unwrap()
            .write_all(b"ab")
            .unwrap();
        assert_eq!(fs::read_to_string(&path).unwrap(), "ab");
    }

    #[test]
    fn strict_rejects_writable_file() {
        let tmp = tmp();
        let path = create_file(&tmp, "file", 0o666);
        let err = strict().open(&path).unwrap_err();
        assert!(matches!(err, SafeFsError::WritableByOthers { .. }));
        SafeOpenOptions::new().open(&path).unwrap();
    }

    #[test]
    fn strict_rejects_writable_directory() {
        let tmp = tmp();
        create_dir(&tmp, "dir", 0o777);
        let path = create_file(&tmp, "dir/file", 0o600);
        let err = strict().open(&path).unwrap_err();
        assert!(matches!(err, SafeFsError::WritableByOthers { .. }));
    }

    #[test]
    fn strict_accepts_sticky_directory() {
        let tmp = tmp();
        create_dir(&tmp, "dir", 0o1777);
        let path = create_file(&tmp, "dir/file", 0o600);
        strict().open(&path).unwrap();
    }

    #[test]
    fn strict_rejects_hardlinked_file() {
        let tmp = tmp();
        let path = create_file(&tmp, "file", 0o600);
        fs::hard_link(&path, tmp.path().join("other")).unwrap();
        let err = strict().open(&path).unwrap_err();
        assert!(matches!(err, SafeFsError::HardLinked { nlink: 2, .. }));
    }

    #[test]
    fn rejects_directory() {
        let tmp = tmp();
        let path = create_dir(&tmp, "dir", 0o700);
        let err = SafeOpenOptions::new().open(&path).unwrap_err();
        assert!(matches!(err, SafeFsError::NotFile(_)));
    }

    #[test]
    fn rejects_parent_dir_component() {
        let tmp = tmp();
        create_dir(&tmp, "dir", 0o700);
        create_file(&tmp, "file", 0o600);
        let err = SafeOpenOptions::new()
            .open(tmp.path().join("dir/../file"))
            .unwrap_err();
        assert!(matches!(err, SafeFsError::InvalidPathComponent(_)));
    }

    #[test]
    fn chdir_enters_directory() {
        let tmp = tmp();
        let dir = create_dir(&tmp, "dir", 0o700);
        let saved = std::env::current_dir().unwrap();
        safe_chdir(&dir, false).unwrap();
        let cwd = std::env::current_dir().unwrap();
        std::env::set_current_dir(saved).unwrap();
        assert_eq!(cwd, dir.canonicalize().unwrap());
    }

    #[test]
    fn chdir_rejects_file() {
        let tmp = tmp();
        let path = create_file(&tmp, "file", 0o600);
        let err = safe_chdir(&path, false).unwrap_err();
        assert!(matches!(err, SafeFsError::NotDirectory(_)));
    }
}
