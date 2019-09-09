// Copyright 2019 Normation SAS
//
// This file is part of Rudder.
//
// Rudder is free software: you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// In accordance with the terms of section 7 (7. Additional Terms.) of
// the GNU General Public License version 3, the copyright holders add
// the following Additional permissions:
// Notwithstanding to the terms of section 5 (5. Conveying Modified Source
// Versions) and 6 (6. Conveying Non-Source Forms.) of the GNU General
// Public License version 3, when you create a Related Module, this
// Related Module is not considered as a part of the work and may be
// distributed under the license agreement of your choice.
// A "Related Module" means a set of sources files including their
// documentation that, without modification of the Source Code, enables
// supplementary functions or services in addition to those offered by
// the Software.
//
// Rudder is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with Rudder.  If not, see <http://www.gnu.org/licenses/>.

use crate::{error::Error, hashing::HashType, JobConfig};
use chrono::{Duration, Utc};
use hex;
use openssl::{
    error::ErrorStack,
    pkey::{PKey, Public},
    rsa::Rsa,
    sign::Verifier,
};
use regex::Regex;
use serde::Deserialize;
use std::{
    fmt, fs,
    io::{BufRead, Read},
    path::Path,
    str,
    str::FromStr,
    sync::Arc,
};
use warp::{body::FullBody, http::StatusCode, Buf};

#[derive(Debug)]
pub struct Metadata {
    pub header: String,
    pub algorithm: HashType,
    pub digest: String,
    pub hash_value: String,
    pub short_pubkey: String,
    pub hostname: String,
    pub key_date: String,
    pub key_id: String,
}

impl fmt::Display for Metadata {
    fn fmt(&self, f: &mut fmt::Formatter) -> fmt::Result {
        writeln!(f, "header={}", &self.header)?;
        writeln!(f, "algorithm={}", &self.algorithm)?;
        writeln!(f, "digest={}", &self.digest)?;
        writeln!(f, "hash_value={}", &self.hash_value)?;
        writeln!(f, "short_pubkey={}", &self.short_pubkey)?;
        writeln!(f, "hostname={}", &self.hostname)?;
        writeln!(f, "keydate={}", &self.key_date)?;
        writeln!(f, "keyid={}", &self.key_id)
    }
}

impl FromStr for Metadata {
    type Err = Error;

    fn from_str(s: &str) -> Result<Self, Self::Err> {
        if Metadata::parse_value("header", s).unwrap() != "rudder-signature-v1" {
            return Err(Error::InvalidHeader);
        }
        Ok(Metadata {
            header: Metadata::parse_value("header", s)?,
            algorithm: HashType::from_str(s)?,
            digest: Metadata::parse_value("digest", s)?,
            hash_value: Metadata::parse_value("hash_value", s)?,
            short_pubkey: Metadata::parse_value("short_pubkey", s)?,
            hostname: Metadata::parse_value("hostname", s)?,
            key_date: Metadata::parse_value("keydate", s)?,
            key_id: Metadata::parse_value("keyid", s)?,
        })
    }
}

impl Metadata {
    fn parse_value(key: &str, file: &str) -> Result<String, Error> {
        let regex_key = Regex::new(&format!(r"{}=(?P<key>[^\n]+)\n", key)).unwrap();

        match regex_key.captures(&file) {
            Some(capture) => match capture.name("key") {
                Some(x) => Ok(x.as_str().to_string()),
                _ => Err(Error::InvalidHeader),
            },
            None => Err(Error::InvalidHeader),
        }
    }
}

pub fn validate_signature(
    file: &[u8],
    pubkey: PKey<Public>,
    hash_type: HashType,
    digest: &[u8],
) -> Result<bool, ErrorStack> {
    let mut verifier = Verifier::new(hash_type.to_openssl_hash(), &pubkey).unwrap();
    verifier.update(file).unwrap();
    verifier.verify(digest)
}

pub fn metadata_parser(buf: &mut FullBody) -> Result<Metadata, Error> {
    let mut metadata: Vec<u8> = Vec::new();
    let _ = buf.reader().read_to_end(&mut metadata)?;
    Metadata::from_str(str::from_utf8(&metadata)?)
}

fn file_writer(buf: &mut FullBody, path: &Path, job_config: Arc<JobConfig>) -> Result<(), Error> {
    let mut file_content: Vec<u8> = vec![];

    buf.by_ref().reader().consume(1); // skip the line feed
    buf.reader().read_to_end(&mut file_content).unwrap();

    Ok(fs::write(
        job_config.cfg.shared_files.path.join(path),
        file_content,
    )?)
}

fn get_pubkey(pubkey: &str) -> Result<PKey<Public>, ErrorStack> {
    PKey::from_rsa(Rsa::public_key_from_pem_pkcs1(
        format!(
            "-----BEGIN RSA PUBLIC KEY-----\n{}\n-----END RSA PUBLIC KEY-----\n",
            pubkey
        )
        .as_bytes(),
    )?)
}

#[derive(Deserialize, Debug)]
pub struct SharedFilesPutParams {
    ttl: String,
}

impl SharedFilesPutParams {
    #[cfg(test)]
    fn new(ttl: &str) -> Self {
        Self {
            ttl: ttl.to_string(),
        }
    }

    fn ttl(&self) -> Result<Duration, Error> {
        if Regex::new(r"^([0-9]+)$").unwrap().is_match(&self.ttl) {
            return Ok(Duration::seconds(self.ttl.parse::<i64>().unwrap()));
        };

        let regex_numbers = Regex::new(r"^(?:(?P<days>\d+)(?:d|days|day))?\s*(?:(?P<hours>\d+)(?:h|hours|hour))?\s*(?:(?P<minutes>\d+)(?:m|minutes|minute))?\s*(?:(?P<seconds>\d+)(?:s|seconds|second))?$").unwrap();
        fn parse_time<'t>(cap: &regex::Captures<'t>, n: &str) -> Result<i64, Error> {
            Ok(match cap.name(n) {
                Some(s) => s.as_str().parse::<i64>()?,
                None => 0,
            })
        }

        if let Some(cap) = regex_numbers.captures(&self.ttl) {
            let s = parse_time(&cap, "seconds")?;
            let m = parse_time(&cap, "minutes")?;
            let h = parse_time(&cap, "hours")?;
            let d = parse_time(&cap, "days")?;

            Ok(
                Duration::seconds(s)
                    + Duration::minutes(m)
                    + Duration::hours(h)
                    + Duration::days(d),
            )
        } else {
            Err(Error::InvalidTtl(self.ttl.clone()))
        }
    }
}

pub fn put(
    metadata_string: String,
    target_id: String,
    source_id: String,
    file_id: String,
    params: SharedFilesPutParams,
    job_config: Arc<JobConfig>,
    mut buf: FullBody,
) -> Result<StatusCode, Error> {
    // Removal timestamp = now + ttl
    let timestamp = match params.ttl() {
        Ok(ttl) => Utc::now() + ttl,
        Err(_x) => return Ok(StatusCode::INTERNAL_SERVER_ERROR),
    };

    let meta = Metadata::from_str(&metadata_string).unwrap();
    let pubkey = get_pubkey(&meta.short_pubkey)?;
    let (file, hash_type) = (buf.by_ref(), meta.algorithm);
    let file_path = job_config
        .cfg
        .shared_files
        .path
        .join(&target_id)
        .join(&source_id)
        .join(format!("{}.metadata", file_id));

    if hash_type.hash(&pubkey.public_key_to_der()?) != meta.digest {
        return Ok(StatusCode::NOT_FOUND);
    }

    if validate_signature(
        file.bytes(),
        pubkey,
        hash_type,
        &hex::decode(meta.digest).unwrap(),
    )? {
        return Ok(StatusCode::INTERNAL_SERVER_ERROR);
    }

    if !job_config
        .nodes
        .read()
        .expect("Cannot read nodes list")
        .is_subnode(&source_id)
    {
        return Ok(StatusCode::NOT_FOUND);
    }

    fs::create_dir_all(
        job_config
            .cfg
            .shared_files
            .path
            .join(&target_id)
            .join(&source_id),
    )
    .unwrap(); // create folders if they don't exist
    fs::write(
        &file_path,
        format!("{}expires={}\n", metadata_string, timestamp),
    )
    .expect("Unable to write file");
    file_writer(buf.by_ref(), &file_path, job_config.clone())?;
    Ok(StatusCode::OK)
}

#[derive(Deserialize, Debug)]
pub struct SharedFilesHeadParams {
    hash: String,
}

pub fn head(
    target_id: String,
    source_id: String,
    file_id: String,
    params: SharedFilesHeadParams,
    job_config: Arc<JobConfig>,
) -> Result<StatusCode, Error> {
    let file_path = job_config
        .cfg
        .shared_files
        .path
        .join(&target_id)
        .join(&source_id)
        .join(format!("{}.metadata", file_id));

    if !file_path.exists() {
        return Ok(StatusCode::NOT_FOUND);
    }

    Ok(
        if Metadata::parse_value("hash_value", &fs::read_to_string(file_path)?)? == params.hash {
            StatusCode::OK
        } else {
            StatusCode::NOT_FOUND
        },
    )
}

#[cfg(test)]
mod tests {
    use super::*;
    use openssl::sign::Signer;

    #[test]
    pub fn it_writes_the_metadata() {
        let metadata = Metadata {
            header: "rudder-signature-v1".to_string(),
            algorithm: HashType::Sha256,
            digest: "8ca9efc5752e133e2e80e2661c176fa50f".to_string(),
            hash_value: "a75fda39a7af33eb93ab1c74874dcf66d5761ad30977368cf0c4788cf5bfd34f"
                .to_string(),
            short_pubkey: "shortpubkey".to_string(),
            hostname: "ubuntu-18-04-64".to_string(),
            key_date: "2018-10-3118:21:43.653257143".to_string(),
            key_id: "B29D02BB".to_string(),
        };

        assert_eq!(format!("{}", metadata), "header=rudder-signature-v1\nalgorithm=sha256\ndigest=8ca9efc5752e133e2e80e2661c176fa50f\nhash_value=a75fda39a7af33eb93ab1c74874dcf66d5761ad30977368cf0c4788cf5bfd34f\nshort_pubkey=shortpubkey\nhostname=ubuntu-18-04-64\nkeydate=2018-10-3118:21:43.653257143\nkeyid=B29D02BB\n");
    }

    #[test]
    pub fn it_validates_signatures() {
        // Generate a keypair
        let k0 = Rsa::generate(2048).unwrap();
        let k0pkey = k0.public_key_to_pem().unwrap();
        let k1 = Rsa::public_key_from_pem(&k0pkey).unwrap();

        let keypriv = PKey::from_rsa(k0).unwrap();
        let keypub = PKey::from_rsa(k1).unwrap();

        let data = b"hello, world!";

        // Sign the data
        let mut signer = Signer::new(HashType::Sha512.to_openssl_hash(), &keypriv).unwrap();
        signer.update(data).unwrap();

        let signature = signer.sign_to_vec().unwrap();

        assert!(validate_signature(data, keypub, HashType::Sha512, &signature).unwrap());
    }

    #[test]
    pub fn it_parses_ttl() {
        assert_eq!(
            SharedFilesPutParams::new("1h 7s").ttl().unwrap(),
            Duration::hours(1) + Duration::seconds(7)
        );
        assert_eq!(
            SharedFilesPutParams::new("1hour 2minutes").ttl().unwrap(),
            Duration::hours(1) + Duration::minutes(2)
        );
        assert_eq!(
            SharedFilesPutParams::new("1d 7seconds").ttl().unwrap(),
            Duration::days(1) + Duration::seconds(7)
        );
        assert_eq!(
            SharedFilesPutParams::new("9136").ttl().unwrap(),
            Duration::seconds(9136)
        );
        assert!(SharedFilesPutParams::new("913j").ttl().is_err());
        assert!(SharedFilesPutParams::new("913b83").ttl().is_err());
        assert!(SharedFilesPutParams::new("913h 89j").ttl().is_err());
    }
}
