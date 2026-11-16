//! TOCTOU-safe file handling.
//!
//! The path is resolved one component at a time with `openat(2)`, always
//! relative to a file descriptor of the previously validated directory, so a
//! component cannot be swapped between the check and the use.
//!
//! Trust policy (an object is "trusted" when owned by root or the effective uid):
//!
//! * every traversed directory must be trusted and must not be writable by
//!   group/others, unless it has the sticky bit (e.g. `/tmp`);
//! * symlinks are resolved by this module, and only if
//!   the symlink itself is trusted. At most [`MAX_SYMLINKS`] are followed;
//! * the final file must be a regular file, trusted, not writable by
//!   group/others, and must not have other hard links;
//! * new files are created with `O_CREAT | O_EXCL`.
//!
//! For relative paths, the current working directory is validated but not
//! its ancestors.
//!
//! Linux-only: relies on `O_PATH` and `readlinkat(fd, "")`.
//!
//! # Examples
//!
//! Open an existing file read-only (the default):
//!
//! ```no_run
//! use std::io::Read;
//! use rudder_module_type::safefs::SafeOpenOptions;
//!
//! let mut file = SafeOpenOptions::new().open("/etc/hostname")?;
//! let mut content = String::new();
//! file.read_to_string(&mut content)?;
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
//! match SafeOpenOptions::new().open("/tmp/some/file") {
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
    process::geteuid,
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
            SafeFsError::Io(err) => Some(err),
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
}

impl Default for SafeOpenOptions {
    fn default() -> Self {
        Self {
            read: true,
            write: false,
            truncate: false,
            create: false,
            mode: 0o600,
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

    pub fn open<P: AsRef<Path>>(&self, path: P) -> Result<File, SafeFsError> {
        if self.truncate && !self.write {
            return Err(SafeFsError::Io(std::io::Error::new(
                std::io::ErrorKind::InvalidInput,
                "truncate requires write access",
            )));
        }
        walk(path.as_ref(), self).map(File::from)
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
}

fn trusted(uid: u32, euid: u32) -> bool {
    uid == 0 || uid == euid
}

fn check_dir(st: &Stat, path: &Path, euid: u32) -> Result<(), SafeFsError> {
    if !trusted(st.st_uid, euid) {
        return Err(SafeFsError::NotOwned {
            path: path.to_owned(),
            owner: st.st_uid,
            euid,
        });
    }
    let sticky = st.st_mode & S_ISVTX != 0;
    if st.st_mode & WRITABLE_BY_OTHERS != 0 && !sticky {
        return Err(SafeFsError::WritableByOthers {
            path: path.to_owned(),
            mode: st.st_mode,
        });
    }
    Ok(())
}

fn check_file(st: &Stat, path: &Path, euid: u32) -> Result<(), SafeFsError> {
    if !trusted(st.st_uid, euid) {
        return Err(SafeFsError::NotOwned {
            path: path.to_owned(),
            owner: st.st_uid,
            euid,
        });
    }
    if st.st_mode & WRITABLE_BY_OTHERS != 0 {
        return Err(SafeFsError::WritableByOthers {
            path: path.to_owned(),
            mode: st.st_mode,
        });
    }
    let nlink = st.st_nlink;
    if nlink > 1 {
        return Err(SafeFsError::HardLinked {
            path: path.to_owned(),
            nlink,
        });
    }
    Ok(())
}

fn open_root(euid: u32) -> Result<OwnedFd, SafeFsError> {
    let fd = openat(CWD, "/", DIR_FLAGS, Mode::empty())?;
    check_dir(&fstat(&fd)?, Path::new("/"), euid)?;
    Ok(fd)
}

/// Re-open an already validated regular file for I/O, and make sure it is
/// still the same inode.
fn reopen(
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

fn create_new(
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
    let st = fstat(&fd)?;
    if !FileType::from_raw_mode(st.st_mode).is_file() {
        return Err(SafeFsError::NotFile(shown.to_owned()));
    }
    check_file(&st, shown, euid)?;
    Ok(fd)
}

fn walk(path: &Path, opts: &SafeOpenOptions) -> Result<OwnedFd, SafeFsError> {
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
    if pending.is_empty() {
        return Err(SafeFsError::PathHasNoFilename(path.to_owned()));
    }
    if path.as_os_str().as_bytes().ends_with(b"/") {
        return Err(SafeFsError::InvalidPathComponent(path.to_owned()));
    }

    let euid = geteuid().as_raw();
    let (mut cur, mut shown) = if path.has_root() {
        (open_root(euid)?, PathBuf::from("/"))
    } else {
        let fd = openat(CWD, ".", DIR_FLAGS, Mode::empty())?;
        check_dir(&fstat(&fd)?, Path::new("."), euid)?;
        (fd, PathBuf::from("."))
    };
    let mut links = 0;

    while let Some(step) = pending.pop_front() {
        let last = pending.is_empty();

        let name = match step {
            Step::Parent => {
                cur = openat(&cur, "..", DIR_FLAGS, Mode::empty())?;
                match shown.components().next_back() {
                    Some(Component::Normal(_)) => {
                        shown.pop();
                    }
                    _ => shown.push(".."),
                }
                check_dir(&fstat(&cur)?, &shown, euid)?;
                continue;
            }
            Step::Name(name) => name,
        };
        shown.push(&name);

        let fd = match openat(&cur, &name, PATH_FLAGS, Mode::empty()) {
            Ok(fd) => fd,
            Err(Errno::NOENT) if last && opts.create => {
                return create_new(&cur, &name, &shown, opts, euid);
            }
            Err(e) => return Err(e.into()),
        };
        let st = fstat(&fd)?;

        match FileType::from_raw_mode(st.st_mode) {
            FileType::Symlink => {
                if !trusted(st.st_uid, euid) {
                    return Err(SafeFsError::SymLinkNotOwned {
                        path: shown,
                        owner: st.st_uid,
                        euid,
                    });
                }
                links += 1;
                if links > MAX_SYMLINKS {
                    return Err(SafeFsError::TooManySymlinks(path.to_owned()));
                }
                let target = readlinkat(&fd, "", Vec::new())?;
                let target = PathBuf::from(OsString::from_vec(target.into_bytes()));

                shown.pop();
                if target.has_root() {
                    cur = open_root(euid)?;
                    shown = PathBuf::from("/");
                }
                for comp in target.components().rev() {
                    match comp {
                        Component::Normal(n) => pending.push_front(Step::Name(n.to_owned())),
                        Component::ParentDir => pending.push_front(Step::Parent),
                        Component::CurDir | Component::RootDir | Component::Prefix(_) => {}
                    }
                }
            }
            FileType::Directory if !last => {
                check_dir(&st, &shown, euid)?;
                cur = fd;
            }
            FileType::RegularFile if last => {
                check_file(&st, &shown, euid)?;
                return reopen(&cur, &name, &st, &shown, opts);
            }
            _ if last => return Err(SafeFsError::NotFile(shown)),
            _ => return Err(SafeFsError::NotDirectory(shown)),
        }
    }

    Err(SafeFsError::NotFile(path.to_owned()))
}

#[cfg(test)]
mod tests {
    use super::*;

    use std::fs::{self, File, Permissions};
    use std::io::{Read, Write};
    use std::os::unix::fs::{MetadataExt, PermissionsExt, symlink};

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

    fn write_file(path: &Path, content: &[u8]) {
        File::create(path).unwrap().write_all(content).unwrap();
        fs::set_permissions(path, Permissions::from_mode(0o600)).unwrap();
    }

    fn read() -> SafeOpenOptions {
        SafeOpenOptions::new()
    }

    fn read_to_string(mut f: File) -> String {
        let mut s = String::new();
        f.read_to_string(&mut s).unwrap();
        s
    }

    #[test]
    fn opens_regular_file() {
        let tmp = tmp();
        let path = create_file(&tmp, "file", 0o644);
        let f = read().open(&path).unwrap();
        assert_eq!(read_to_string(f), "data");
    }

    #[test]
    fn read_only_open_cannot_write() {
        let tmp = tmp();
        let path = create_file(&tmp, "file", 0o644);
        let mut f = read().open(&path).unwrap();
        assert!(f.write_all(b"x").is_err());
    }

    #[test]
    fn missing_file_without_create_fails() {
        let tmp = tmp();
        let err = read().open(tmp.path().join("missing")).unwrap_err();
        assert!(
            matches!(&err, SafeFsError::Io(e) if e.kind() == std::io::ErrorKind::NotFound),
            "{}",
            err
        );
    }

    #[test]
    fn creates_missing_file_with_mode() {
        let tmp = tmp();
        let path = tmp.path().join("new");
        let mut f = safe_open(&path, &Permissions::from_mode(0o640)).unwrap();
        f.write_all(b"hello").unwrap();
        let mode = fs::metadata(&path).unwrap().mode() & 0o7777;
        // umask may remove bits but never adds any.
        assert_eq!(mode & !0o640, 0, "mode {mode:o}");
        assert_eq!(fs::read_to_string(&path).unwrap(), "hello");
    }

    #[test]
    fn default_create_mode_is_0600() {
        let tmp = tmp();
        let path = tmp.path().join("new");
        read().write(true).create(true).open(&path).unwrap();
        let mode = fs::metadata(&path).unwrap().mode() & 0o7777;
        assert_eq!(mode & !0o600, 0, "mode {mode:o}");
    }

    #[test]
    fn rejects_world_writable_file() {
        let tmp = tmp();
        let path = create_file(&tmp, "file", 0o646);
        let err = read().open(&path).unwrap_err();
        assert!(
            matches!(err, SafeFsError::WritableByOthers { .. }),
            "{}",
            err
        );
    }

    #[test]
    fn rejects_group_writable_file() {
        let tmp = tmp();
        let path = create_file(&tmp, "file", 0o664);
        let err = read().open(&path).unwrap_err();
        assert!(
            matches!(err, SafeFsError::WritableByOthers { .. }),
            "{}",
            err
        );
    }

    #[test]
    fn rejects_world_writable_directory() {
        let tmp = tmp();
        create_dir(&tmp, "sub", 0o777);
        let path = tmp.path().join("sub/file");
        File::create(&path).unwrap();
        let err = read().open(&path).unwrap_err();
        assert!(
            matches!(err, SafeFsError::WritableByOthers { .. }),
            "{}",
            err
        );
    }

    #[test]
    fn rejects_group_writable_directory() {
        let tmp = tmp();
        create_dir(&tmp, "sub", 0o770);
        let path = tmp.path().join("sub/file");
        File::create(&path).unwrap();
        let err = read().open(&path).unwrap_err();
        assert!(
            matches!(err, SafeFsError::WritableByOthers { .. }),
            "{}",
            err
        );
    }

    #[test]
    fn accepts_sticky_world_writable_directory() {
        let tmp = tmp();
        create_dir(&tmp, "sticky", 0o1777);
        let path = tmp.path().join("sticky/file");
        write_file(&path, b"data");
        read().open(&path).unwrap();
    }

    #[test]
    fn rejects_hardlinked_file() {
        let tmp = tmp();
        let path = create_file(&tmp, "file", 0o600);
        fs::hard_link(&path, tmp.path().join("other")).unwrap();
        let err = read().open(&path).unwrap_err();
        assert!(
            matches!(err, SafeFsError::HardLinked { nlink: 2, .. }),
            "{}",
            err
        );
    }

    #[test]
    fn rejects_directory_as_final_component() {
        let tmp = tmp();
        let path = create_dir(&tmp, "sub", 0o755);
        let err = read().open(&path).unwrap_err();
        assert!(matches!(err, SafeFsError::NotFile(_)), "{}", err);
    }

    #[test]
    fn rejects_file_as_intermediate_component() {
        let tmp = tmp();
        create_file(&tmp, "file", 0o600);
        let err = read().open(tmp.path().join("file/x")).unwrap_err();
        assert!(matches!(err, SafeFsError::NotDirectory(_)), "{}", err);
    }

    #[test]
    fn rejects_parent_dir_in_input() {
        let tmp = tmp();
        create_dir(&tmp, "sub", 0o755);
        create_file(&tmp, "file", 0o600);
        let err = read().open(tmp.path().join("sub/../file")).unwrap_err();
        assert!(
            matches!(err, SafeFsError::InvalidPathComponent(_)),
            "{}",
            err
        );
    }

    #[test]
    fn rejects_trailing_slash() {
        let tmp = tmp();
        create_file(&tmp, "file", 0o600);
        let path = format!("{}/file/", tmp.path().display());
        let err = read().open(path).unwrap_err();
        assert!(
            matches!(err, SafeFsError::InvalidPathComponent(_)),
            "{}",
            err
        );
    }

    #[test]
    fn rejects_root() {
        let err = read().open("/").unwrap_err();
        assert!(matches!(err, SafeFsError::PathHasNoFilename(_)), "{}", err);
    }

    #[test]
    fn follows_final_symlink() {
        let tmp = tmp();
        create_file(&tmp, "target", 0o600);
        symlink("target", tmp.path().join("link")).unwrap();
        let f = read().open(tmp.path().join("link")).unwrap();
        assert_eq!(read_to_string(f), "data");
    }

    #[test]
    fn follows_intermediate_symlink() {
        let tmp = tmp();
        create_dir(&tmp, "real", 0o755);
        write_file(&tmp.path().join("real/file"), b"inner");
        symlink("real", tmp.path().join("dirlink")).unwrap();
        let f = read().open(tmp.path().join("dirlink/file")).unwrap();
        assert_eq!(read_to_string(f), "inner");
    }

    #[test]
    fn follows_relative_symlink_with_parent_dir() {
        let tmp = tmp();
        create_dir(&tmp, "sub", 0o755);
        create_dir(&tmp, "other", 0o755);
        write_file(&tmp.path().join("other/file"), b"other");
        symlink("../other/file", tmp.path().join("sub/link")).unwrap();
        let f = read().open(tmp.path().join("sub/link")).unwrap();
        assert_eq!(read_to_string(f), "other");
    }

    #[test]
    fn follows_absolute_symlink() {
        let tmp = tmp();
        let target = create_file(&tmp, "target", 0o600);
        symlink(&target, tmp.path().join("link")).unwrap();
        let f = read().open(tmp.path().join("link")).unwrap();
        assert_eq!(read_to_string(f), "data");
    }

    #[test]
    fn follows_chained_symlinks() {
        let tmp = tmp();
        create_file(&tmp, "target", 0o600);
        symlink("target", tmp.path().join("l1")).unwrap();
        symlink("l1", tmp.path().join("l2")).unwrap();
        read().open(tmp.path().join("l2")).unwrap();
    }

    #[test]
    fn symlink_target_is_checked() {
        let tmp = tmp();
        create_file(&tmp, "target", 0o646);
        symlink("target", tmp.path().join("link")).unwrap();
        let err = read().open(tmp.path().join("link")).unwrap_err();
        assert!(
            matches!(err, SafeFsError::WritableByOthers { .. }),
            "{}",
            err
        );
    }

    #[test]
    fn symlink_to_directory_as_final_is_rejected() {
        let tmp = tmp();
        create_dir(&tmp, "sub", 0o755);
        symlink("sub", tmp.path().join("link")).unwrap();
        let err = read().open(tmp.path().join("link")).unwrap_err();
        assert!(matches!(err, SafeFsError::NotFile(_)), "{}", err);
    }

    #[test]
    fn detects_symlink_loop() {
        let tmp = tmp();
        symlink("b", tmp.path().join("a")).unwrap();
        symlink("a", tmp.path().join("b")).unwrap();
        let err = read().open(tmp.path().join("a")).unwrap_err();
        assert!(matches!(err, SafeFsError::TooManySymlinks(_)), "{}", err);
    }

    #[test]
    fn dangling_symlink_with_create_creates_target() {
        let tmp = tmp();
        symlink("new", tmp.path().join("link")).unwrap();
        read()
            .write(true)
            .create(true)
            .open(tmp.path().join("link"))
            .unwrap();
        assert!(tmp.path().join("new").is_file());
    }

    #[test]
    fn write_without_truncate_overwrites_in_place() {
        let tmp = tmp();
        let path = tmp.path().join("file");
        write_file(&path, b"0123456789");
        let mut f = read().write(true).open(&path).unwrap();
        f.write_all(b"ab").unwrap();
        assert_eq!(fs::read_to_string(&path).unwrap(), "ab23456789");
    }

    #[test]
    fn truncate_replaces_content() {
        let tmp = tmp();
        let path = tmp.path().join("file");
        write_file(&path, b"0123456789");
        let mut f = read().write(true).truncate(true).open(&path).unwrap();
        f.write_all(b"ab").unwrap();
        assert_eq!(fs::read_to_string(&path).unwrap(), "ab");
    }

    #[test]
    fn truncate_with_create_on_missing_file() {
        let tmp = tmp();
        let path = tmp.path().join("new");
        let mut f = read()
            .write(true)
            .truncate(true)
            .create(true)
            .open(&path)
            .unwrap();
        f.write_all(b"ab").unwrap();
        assert_eq!(fs::read_to_string(&path).unwrap(), "ab");
    }

    #[test]
    fn truncate_without_write_is_rejected() {
        let tmp = tmp();
        let path = tmp.path().join("file");
        write_file(&path, b"0123456789");
        let err = read().truncate(true).open(&path).unwrap_err();
        assert!(
            matches!(&err, SafeFsError::Io(e) if e.kind() == std::io::ErrorKind::InvalidInput),
            "{}",
            err
        );
        assert_eq!(fs::read_to_string(&path).unwrap(), "0123456789");
    }

    #[test]
    fn truncate_does_not_apply_to_rejected_file() {
        let tmp = tmp();
        let path = tmp.path().join("file");
        write_file(&path, b"0123456789");
        fs::hard_link(&path, tmp.path().join("other")).unwrap();
        let err = read().write(true).truncate(true).open(&path).unwrap_err();
        assert!(matches!(err, SafeFsError::HardLinked { .. }), "{}", err);
        assert_eq!(fs::read_to_string(&path).unwrap(), "0123456789");
    }
}
