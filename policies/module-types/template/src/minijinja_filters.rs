// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2025 Normation SAS

use base64::prelude::*;
use minijinja::{Error, ErrorKind};
use regex::Regex;
use sha1::Sha1;
use sha2::{Digest, Sha256, Sha512};
use shlex::try_quote;
use std::{ffi::OsStr, path::Path};
use urlencoding::decode;

pub fn b64encode(plain: String) -> String {
    BASE64_STANDARD.encode(plain)
}

pub fn b64decode(encoded: String) -> Result<String, Error> {
    let d = BASE64_STANDARD.decode(&encoded).map_err(|e| {
        Error::new(
            ErrorKind::CannotDeserialize,
            format!("cannot decode base64: {encoded}"),
        )
        .with_source(e)
    })?;
    Ok(String::from_utf8_lossy(&d).to_string())
}

pub fn basename(path: String) -> Result<String, Error> {
    let file = match Path::new(&path).file_name() {
        Some(f) => f,
        None => {
            let n = path.chars().count();
            let last: Vec<char> = path.chars().rev().take(3).collect();
            let res = match last[..] {
                ['.', '.', '/'] => "..",
                ['.', '/'] => ".",
                ['.'] if n == 1 => ".",
                ['.', '.'] if n == 2 => "..",
                _ => "",
            };
            OsStr::new(res)
        }
    };
    let file = file
        .to_str()
        .ok_or_else(|| Error::new(ErrorKind::CannotUnpack, "invalid UTF-8"))?;
    Ok(String::from(file))
}

pub fn dirname(path: String) -> Result<String, Error> {
    let file = Path::new(&path).parent().unwrap_or_else(|| {
        if path.is_empty() {
            Path::new("")
        } else {
            Path::new("/")
        }
    });
    let file = file
        .to_str()
        .ok_or_else(|| Error::new(ErrorKind::CannotUnpack, "invalid UTF-8"))?;
    Ok(String::from(file))
}

pub fn urldecode(encoded: String) -> Result<String, Error> {
    let d = decode(&encoded).map_err(|e| {
        Error::new(
            ErrorKind::CannotDeserialize,
            format!("cannot decode url encoded: {encoded}"),
        )
        .with_source(e)
    })?;
    Ok(String::from(d))
}

pub fn hash(data: String, algorithm: Option<&str>) -> Result<String, Error> {
    let h = match algorithm.unwrap_or("sha-1") {
        "sha-1" | "sha1" => Sha1::digest(data).to_vec(),
        "sha-256" | "sha256" => Sha256::digest(data).to_vec(),
        "sha-512" | "sha512" => Sha512::digest(data).to_vec(),
        x => {
            return Err(Error::new(
                ErrorKind::UnknownMethod,
                format!(
                    "hash filter does not implement the '{x}' cryptographic hash function"
                ),
            ));
        }
    };
    Ok(base16ct::lower::encode_string(&h))
}

pub fn quote(data: String) -> Result<String, Error> {
    let s = try_quote(&data).map_err(|e| {
        Error::new(
            ErrorKind::BadSerialization,
            format!("could not quote: '{data}'"),
        )
        .with_source(e)
    })?;
    Ok(String::from(s))
}

pub fn regex_escape(data: String) -> String {
    regex::escape(&data)
}

pub fn regex_replace(
    data: String,
    regex: String,
    re: String,
    n: Option<usize>,
) -> Result<String, Error> {
    let n = n.unwrap_or(1);
    let r = Regex::new(&regex).map_err(|e| {
        Error::new(
            ErrorKind::SyntaxError,
            format!("could not compile regex: {regex}"),
        )
        .with_source(e)
    })?;
    Ok(r.replacen(&data, n, re).to_string())
}

#[cfg(test)]
mod tests {
    use super::*;
    #[cfg(target_family = "unix")]
    use rudder_commons_test::module_type::unix;
    #[cfg(target_family = "unix")]
    use rudder_module_type::{Outcome, PolicyMode};
    #[cfg(target_family = "unix")]
    use std::fs::{self, read_to_string};
    #[cfg(target_family = "unix")]
    use tempfile::tempdir;

    #[cfg(target_family = "unix")]
    const BIN: &str = concat!("../../../target/debug/", env!("CARGO_PKG_NAME"));

    #[test]
    fn test_minijinja_basename() {
        assert_eq!(basename("".to_string()).unwrap(), "");
        assert_eq!(basename("/".to_string()).unwrap(), "");
        assert_eq!(basename("/.".to_string()).unwrap(), ".");
        assert_eq!(basename("/..".to_string()).unwrap(), "..");
        assert_eq!(basename(".".to_string()).unwrap(), ".");
        assert_eq!(basename("..".to_string()).unwrap(), "..");
        assert_eq!(basename("afile..".to_string()).unwrap(), "afile..");
        assert_eq!(basename("afile.".to_string()).unwrap(), "afile.");
        assert_eq!(basename("file.txt".to_string()).unwrap(), "file.txt");
        assert_eq!(basename("/tmp/dir/file.".to_string()).unwrap(), "file.");
        assert_eq!(basename("/file.txt".to_string()).unwrap(), "file.txt");
        assert_eq!(basename("/tmp/dir/file".to_string()).unwrap(), "file");
    }

    #[test]
    #[cfg(target_family = "unix")]
    fn test_basename_filter() {
        let root_dir = tempdir().unwrap();
        let test_path = root_dir.path().join("output");
        fs::write(&test_path, "file").unwrap();

        unix::test(
            Path::new(BIN),
            &format!(
                r#"{{"path": "{}", "engine": "{}", "template_src": "{{{{ path|basename }}}}", "data": {{ "path": "/tmp/dir/file" }} }}"#,
                test_path.display(),
                "minijinja"
            ),
            PolicyMode::Audit,
            Ok(Outcome::success()),
        );
        let output = read_to_string(&test_path).unwrap();
        assert_eq!("file", output);
    }

    #[test]
    fn test_minijinja_dirname() {
        assert_eq!(dirname("".to_string()).unwrap(), "");
        assert_eq!(dirname("/".to_string()).unwrap(), "/");
        assert_eq!(dirname(".".to_string()).unwrap(), "");
        assert_eq!(dirname("..".to_string()).unwrap(), "");
        assert_eq!(dirname("/../..".to_string()).unwrap(), "/..");
        assert_eq!(dirname("/file".to_string()).unwrap(), "/");
        assert_eq!(dirname("/tmp/file".to_string()).unwrap(), "/tmp");
        assert_eq!(dirname("/tmp/dir/file".to_string()).unwrap(), "/tmp/dir");
    }

    #[test]
    #[cfg(target_family = "unix")]
    fn test_dirname_filter() {
        let root_dir = tempdir().unwrap();
        let test_path = root_dir.path().join("output");
        fs::write(&test_path, "/tmp/dir").unwrap();

        unix::test(
            Path::new(BIN),
            &format!(
                r#"{{"path": "{}", "engine": "{}", "template_src": "{{{{ path|dirname }}}}", "data": {{ "path": "/tmp/dir/file" }} }}"#,
                test_path.display(),
                "minijinja"
            ),
            PolicyMode::Audit,
            Ok(Outcome::success()),
        );
        let output = read_to_string(&test_path).unwrap();
        assert_eq!("/tmp/dir", output);
    }

    #[test]
    fn test_minijinja_b64decode() {
        assert_eq!(
            b64decode("SGVsbG8sIEZlcnJpcyE=".to_string()).unwrap(),
            "Hello, Ferris!".to_string()
        );
    }

    #[test]
    #[cfg(target_family = "unix")]
    fn test_b64decode_filter() {
        let root_dir = tempdir().unwrap();
        let test_path = root_dir.path().join("output");
        fs::write(&test_path, "Hello, Ferris!").unwrap();

        unix::test(
            Path::new(BIN),
            &format!(
                r#"{{"path": "{}", "engine": "{}", "template_src": "{{{{ encoded_data|b64decode }}}}", "data": {{ "encoded_data": "SGVsbG8sIEZlcnJpcyE=" }} }}"#,
                test_path.display(),
                "minijinja"
            ),
            PolicyMode::Audit,
            Ok(Outcome::success()),
        );
        let output = read_to_string(&test_path).unwrap();
        assert_eq!("Hello, Ferris!", output);
    }

    #[test]
    fn test_minijinja_b64encode() {
        assert_eq!(
            b64encode("Hello, Ferris!".to_string()),
            "SGVsbG8sIEZlcnJpcyE=".to_string()
        )
    }

    #[test]
    #[cfg(target_family = "unix")]
    fn test_b64encode_filter() {
        let root_dir = tempdir().unwrap();
        let test_path = root_dir.path().join("output");
        fs::write(&test_path, "SGVsbG8sIEZlcnJpcyE=").unwrap();

        unix::test(
            Path::new(BIN),
            &format!(
                r#"{{"path": "{}", "engine": "{}", "template_src": "{{{{ plain_data|b64encode }}}}", "data": {{ "plain_data": "Hello, Ferris!" }} }}"#,
                test_path.display(),
                "minijinja"
            ),
            PolicyMode::Audit,
            Ok(Outcome::success()),
        );
        let output = read_to_string(&test_path).unwrap();
        assert_eq!("SGVsbG8sIEZlcnJpcyE=", output);
    }

    #[test]
    fn test_minijinja_urldecode() {
        assert_eq!(
            urldecode("Hello%2C%20Ferris%21".to_string()).unwrap(),
            "Hello, Ferris!".to_string()
        )
    }

    #[test]
    #[cfg(target_family = "unix")]
    fn test_urldecode_filter() {
        let root_dir = tempdir().unwrap();
        let test_path = root_dir.path().join("output");
        fs::write(&test_path, "Hello, Ferris!").unwrap();

        unix::test(
            Path::new(BIN),
            &format!(
                r#"{{"path": "{}", "engine": "{}", "template_src": "{{{{ encoded_data|urldecode }}}}", "data": {{ "encoded_data": "Hello%2C%20Ferris%21" }} }}"#,
                test_path.display(),
                "minijinja"
            ),
            PolicyMode::Audit,
            Ok(Outcome::success()),
        );
        let output = read_to_string(&test_path).unwrap();
        assert_eq!("Hello, Ferris!", output);
    }

    #[test]
    fn test_minijinja_hash() {
        // Default to SHA-1
        assert_eq!(
            hash("Hello, Ferris!".to_string(), None).unwrap(),
            "ba6cf06f051fb25cb1d4e4bc9af114dfc8145aef".to_string()
        );

        assert_eq!(
            hash("Hello, Ferris!".to_string(), Some("sha-1")).unwrap(),
            "ba6cf06f051fb25cb1d4e4bc9af114dfc8145aef".to_string()
        );

        assert_eq!(
            hash("Hello, Ferris!".to_string(), Some("sha-256")).unwrap(),
            "f3060d182343dea84d8f3981ac4671e2db55b146efbcd9eb989b4bf2661596fe".to_string()
        );

        assert_eq!(
        hash("Hello, Ferris!".to_string(), Some("sha-512")).unwrap(),
        "c5b7dafbad6ab5eadb1e310784db224399e116d9d5e14af54e92aad4ee894f153e476d572719965d521d340772db468eb38d6f499e319ffebb5e095b7847d263".to_string()
    );
    }

    #[test]
    #[cfg(target_family = "unix")]
    fn test_hash_filter_with_default() {
        let root_dir = tempdir().unwrap();
        let test_path = root_dir.path().join("output");
        let hash = "ba6cf06f051fb25cb1d4e4bc9af114dfc8145aef";
        fs::write(&test_path, hash).unwrap();

        unix::test(
            Path::new(BIN),
            &format!(
                r#"{{"path": "{}", "engine": "{}", "template_src": "{{{{ data|hash }}}}", "data": {{ "data": "Hello, Ferris!" }} }}"#,
                test_path.display(),
                "minijinja"
            ),
            PolicyMode::Audit,
            Ok(Outcome::success()),
        );
        let output = read_to_string(&test_path).unwrap();
        assert_eq!(hash, output);
    }

    #[test]
    #[cfg(target_family = "unix")]
    fn test_hash_filter_with_sha1() {
        let root_dir = tempdir().unwrap();
        let test_path = root_dir.path().join("output");
        let hash = "ba6cf06f051fb25cb1d4e4bc9af114dfc8145aef";
        fs::write(&test_path, hash).unwrap();

        unix::test(
            Path::new(BIN),
            &format!(
                r#"{{"path": "{}", "engine": "{}", "template_src": "{{{{ data|hash('sha-1') }}}}", "data": {{ "data": "Hello, Ferris!" }} }}"#,
                test_path.display(),
                "minijinja"
            ),
            PolicyMode::Audit,
            Ok(Outcome::success()),
        );
        let output = read_to_string(&test_path).unwrap();
        assert_eq!(hash, output);
    }

    #[test]
    #[cfg(target_family = "unix")]
    fn test_hash_filter_with_sha256() {
        let root_dir = tempdir().unwrap();
        let test_path = root_dir.path().join("output");
        let hash = "f3060d182343dea84d8f3981ac4671e2db55b146efbcd9eb989b4bf2661596fe";
        fs::write(&test_path, hash).unwrap();

        unix::test(
            Path::new(BIN),
            &format!(
                r#"{{"path": "{}", "engine": "{}", "template_src": "{{{{ data|hash('sha-256') }}}}", "data": {{ "data": "Hello, Ferris!" }} }}"#,
                test_path.display(),
                "minijinja"
            ),
            PolicyMode::Audit,
            Ok(Outcome::success()),
        );
        let output = read_to_string(&test_path).unwrap();
        assert_eq!(hash, output);
    }

    #[test]
    #[cfg(target_family = "unix")]
    fn test_hash_filter_with_sha512() {
        let root_dir = tempdir().unwrap();
        let test_path = root_dir.path().join("output");
        let hash = "c5b7dafbad6ab5eadb1e310784db224399e116d9d5e14af54e92aad4ee894f153e476d572719965d521d340772db468eb38d6f499e319ffebb5e095b7847d263";
        fs::write(&test_path, hash).unwrap();

        unix::test(
            Path::new(BIN),
            &format!(
                r#"{{"path": "{}", "engine": "{}", "template_src": "{{{{ data|hash('sha-512') }}}}", "data": {{ "data": "Hello, Ferris!" }} }}"#,
                test_path.display(),
                "minijinja"
            ),
            PolicyMode::Audit,
            Ok(Outcome::success()),
        );
        let output = read_to_string(&test_path).unwrap();
        assert_eq!(hash, output);
    }

    #[test]
    fn test_minijinja_quote() {
        assert_eq!(
            quote("A string to quote".to_string()).unwrap(),
            "'A string to quote'".to_string()
        );
        assert_eq!(quote("".to_string()).unwrap(), "''".to_string());
    }

    #[test]
    #[cfg(target_family = "unix")]
    fn test_quote_filter() {
        let root_dir = tempdir().unwrap();
        let test_path = root_dir.path().join("output");
        let quote = "'A string to quote'";
        fs::write(&test_path, quote).unwrap();

        unix::test(
            Path::new(BIN),
            &format!(
                r#"{{"path": "{}", "engine": "{}", "template_src": "{{{{ data|quote }}}}", "data": {{ "data": "A string to quote" }} }}"#,
                test_path.display(),
                "minijinja"
            ),
            PolicyMode::Audit,
            Ok(Outcome::success()),
        );
        let output = read_to_string(&test_path).unwrap();
        assert_eq!(quote, output);
    }

    #[test]
    fn test_regex_escape() {
        assert_eq!(
            regex_escape("^f.*o(.*)$".to_string()),
            r"\^f\.\*o\(\.\*\)\$".to_string()
        );
    }

    #[test]
    #[cfg(target_family = "unix")]
    fn test_regex_escape_filter() {
        let root_dir = tempdir().unwrap();
        let test_path = root_dir.path().join("output");
        let res = r"\^f\.\*o\(\.\*\)\$";
        fs::write(&test_path, res).unwrap();

        unix::test(
            Path::new(BIN),
            &format!(
                r#"{{"path": "{}", "engine": "{}", "template_src": "{{{{ data|regex_escape }}}}", "data": {{ "data": "^f.*o(.*)$" }} }}"#,
                test_path.display(),
                "minijinja"
            ),
            PolicyMode::Audit,
            Ok(Outcome::success()),
        );
        let output = read_to_string(&test_path).unwrap();
        assert_eq!(res, output);
    }

    #[test]
    fn test_regex_replace() {
        assert_eq!(
            regex_replace(
                "ansible".to_string(),
                "^a.*i(.*)$".to_string(),
                "a$1".to_string(),
                None
            )
            .unwrap(),
            "able".to_string()
        );

        assert_eq!(
            regex_replace(
                "foo=bar=baz".to_string(),
                "=".to_string(),
                ":".to_string(),
                None
            )
            .unwrap(),
            "foo:bar=baz"
        );
    }

    #[test]
    #[cfg(target_family = "unix")]
    fn test_regex_replace_filter() {
        let root_dir = tempdir().unwrap();
        let test_path = root_dir.path().join("output");
        let res = "able";
        fs::write(&test_path, res).unwrap();

        unix::test(
            Path::new(BIN),
            &format!(
                r#"{{"path": "{}", "engine": "{}", "template_src": "{{{{ data|regex_replace('{}', '{}') }}}}", "data": {{ "data": "ansible" }} }}"#,
                test_path.display(),
                "minijinja",
                "^a.*i(.*)$",
                "a$1"
            ),
            PolicyMode::Audit,
            Ok(Outcome::success()),
        );
        let output = read_to_string(&test_path).unwrap();
        assert_eq!(res, output);
    }

    #[test]
    #[cfg(target_family = "unix")]
    fn test_regex_replace_filter_with_n() {
        let root_dir = tempdir().unwrap();
        let test_path = root_dir.path().join("output");
        let res = "able";
        fs::write(&test_path, res).unwrap();

        unix::test(
            Path::new(BIN),
            &format!(
                r#"{{"path": "{}", "engine": "{}", "template_src": "{{{{ data|regex_replace('{}', '{}', 1) }}}}", "data": {{ "data": "ansible" }} }}"#,
                test_path.display(),
                "minijinja",
                "^a.*i(.*)$",
                "a$1"
            ),
            PolicyMode::Audit,
            Ok(Outcome::success()),
        );
        let output = read_to_string(&test_path).unwrap();
        assert_eq!(res, output);
    }
}
