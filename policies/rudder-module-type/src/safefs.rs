pub mod safe_fs {
    use std::{
        fmt::{self, Display},
        fs::{File, Permissions},
        os::fd::{AsFd, OwnedFd},
        path::{Component, Path},
    };

    use rustix::{
        fs::{CWD, FileType, Mode, OFlags, fstat, openat},
        path::Arg,
        process::geteuid,
    };

    #[derive(Debug)]
    pub enum SafeFsError {
        SymLinkNotOwned(u32, u32),
        PathIsWorldWritable(u32),
        Io(std::io::Error),
    }

    impl Display for SafeFsError {
        fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
            match self {
                SafeFsError::SymLinkNotOwned(owned_by, current_uid) => {
                    write!(
                        f,
                        "symlink is owned by uid '{owned_by}', expected root (0) or the effective user (uid '{current_uid}')"
                    )
                }
                SafeFsError::PathIsWorldWritable(mode) => {
                    write!(f, "Path is writable by others (mode {:o})", mode & 0o7777)
                }
                SafeFsError::Io(err) => write!(f, "{err}"),
            }
        }
    }

    impl From<std::io::Error> for SafeFsError {
        fn from(err: std::io::Error) -> Self {
            SafeFsError::Io(err)
        }
    }

    impl From<rustix::io::Errno> for SafeFsError {
        fn from(err: rustix::io::Errno) -> Self {
            SafeFsError::Io(err.into())
        }
    }

    fn open_at<P: Arg, Fd: AsFd>(
        dir: Fd,
        path: P,
        flags: OFlags,
        mode: Mode,
    ) -> Result<OwnedFd, SafeFsError> {
        let fd = openat(dir, path, flags, mode)?;
        let stat = fstat(&fd)?;
        let file_type = FileType::from_raw_mode(stat.st_mode);
        let current_uid = geteuid().as_raw();

        if file_type.is_symlink() {
            if stat.st_uid != 0 && stat.st_uid != current_uid {
                return Err(SafeFsError::SymLinkNotOwned(stat.st_uid, current_uid));
            }
            // TODO: readlinkat() the fd and revalidate the entire path
            return Ok(fd);
        }

        let mode = Mode::from_raw_mode(stat.st_mode);
        if mode.contains(Mode::WOTH) {
            return Err(SafeFsError::PathIsWorldWritable(stat.st_mode));
        }

        Ok(fd)
    }

    pub fn safe_open<P: AsRef<Path>>(path: P, _perms: &Permissions) -> Result<File, SafeFsError> {
        let fd = walk(path)?;
        Ok(File::from(fd))
    }

    fn walk<P: AsRef<Path>>(path: P) -> Result<OwnedFd, SafeFsError> {
        let perms: Mode = 0o700.into();

        let mut components = path.as_ref().components().peekable();
        let mut dir: OwnedFd = if let Some(Component::RootDir) = components.peek() {
            components.next();
            open_at(CWD, "/", OFlags::PATH | OFlags::DIRECTORY, perms)?
        } else {
            open_at(CWD, ".", OFlags::PATH | OFlags::DIRECTORY, perms)?
        };

        // TODO: rewrite with a stack
        while let Some(comp) = components.next() {
            let name = match comp {
                Component::Normal(n) => n,
                Component::CurDir => continue,
                Component::ParentDir => {
                    panic!("'..' not allowed");
                }
                Component::RootDir | Component::Prefix(_) => {
                    panic!("unexpected path component");
                }
            };

            if components.peek().is_some() {
                dir = open_at(&dir, name, OFlags::PATH | OFlags::NOFOLLOW, perms)?;
            } else {
                let fd = open_at(
                    &dir,
                    name,
                    OFlags::RDWR
                        | OFlags::NOFOLLOW
                        | OFlags::NOCTTY
                        | OFlags::NONBLOCK
                        | OFlags::CLOEXEC,
                    perms,
                )?;

                let stat = fstat(&fd)?;
                let file_type = FileType::from_raw_mode(stat.st_mode);
                if !file_type.is_file() {
                    panic!("not a regular file");
                }
                return Ok(fd);
            }
        }

        panic!("path has no file name");
    }

    #[cfg(test)]
    mod tests {
        use super::*;

        use std::fs::{self, File, Permissions};
        use std::os::unix::fs::{PermissionsExt, symlink};

        use rustix::fs::open;
        use tempfile::TempDir;

        fn setup() -> (TempDir, OwnedFd) {
            let tmp = tempfile::tempdir().unwrap();
            let dir = open(
                tmp.path(),
                OFlags::RDONLY | OFlags::DIRECTORY | OFlags::CLOEXEC,
                Mode::empty(),
            )
            .unwrap();
            (tmp, dir)
        }

        fn create_file(tmp: &TempDir, name: &str, mode: u32) {
            let path = tmp.path().join(name);
            File::create(&path).unwrap();
            fs::set_permissions(&path, Permissions::from_mode(mode)).unwrap();
        }

        fn create_dir(tmp: &TempDir, name: &str, mode: u32) {
            let path = tmp.path().join(name);
            fs::create_dir(&path).unwrap();
            fs::set_permissions(&path, Permissions::from_mode(mode)).unwrap();
        }

        fn read_flags() -> OFlags {
            OFlags::RDONLY | OFlags::CLOEXEC
        }

        fn dir_flags() -> OFlags {
            OFlags::RDONLY | OFlags::DIRECTORY | OFlags::CLOEXEC
        }

        #[test]
        fn opens_regular_file() {
            let (tmp, dir) = setup();
            create_file(&tmp, "file", 0o644);

            let res = open_at(&dir, "file", read_flags(), Mode::empty());
            assert!(res.is_ok(), "{:?}", res.err());
        }

        #[test]
        fn rejects_world_writable_file() {
            let (tmp, dir) = setup();
            create_file(&tmp, "file", 0o646);

            let err = open_at(&dir, "file", read_flags(), Mode::empty()).unwrap_err();
            assert!(err.to_string().contains("writable by others"), "{err}");
        }

        #[test]
        fn opens_directory() {
            let (tmp, dir) = setup();
            create_dir(&tmp, "sub", 0o755);

            let res = open_at(&dir, "sub", dir_flags(), Mode::empty());
            assert!(res.is_ok(), "{:?}", res.err());
        }

        #[test]
        fn rejects_world_writable_directory() {
            let (tmp, dir) = setup();
            create_dir(&tmp, "sub", 0o777);

            let err = open_at(&dir, "sub", dir_flags(), Mode::empty()).unwrap_err();
            assert!(err.to_string().contains("writable by others"), "{err}");
        }

        #[test]
        fn follows_symlink_and_checks_target() {
            let (tmp, dir) = setup();
            create_file(&tmp, "target", 0o646);
            symlink("target", tmp.path().join("link")).unwrap();

            let err = open_at(&dir, "link", read_flags(), Mode::empty()).unwrap_err();
            assert!(err.to_string().contains("writable by others"), "{err}");
        }

        #[test]
        fn nofollow_without_o_path_fails_on_symlink() {
            let (tmp, dir) = setup();
            create_file(&tmp, "target", 0o644);
            symlink("target", tmp.path().join("link")).unwrap();

            let res = open_at(&dir, "link", read_flags() | OFlags::NOFOLLOW, Mode::empty());
            assert!(res.is_err());
        }

        fn symlink_flags() -> OFlags {
            OFlags::PATH | OFlags::NOFOLLOW | OFlags::CLOEXEC
        }

        #[test]
        fn accepts_symlink_owned_by_current_user() {
            let (tmp, dir) = setup();
            create_file(&tmp, "target", 0o600);
            symlink("target", tmp.path().join("link")).unwrap();

            let res = open_at(&dir, "link", symlink_flags(), Mode::empty());
            assert!(res.is_ok(), "{:?}", res.err());
        }
    }
}
