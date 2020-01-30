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
use bytes::IntoBuf;
use chrono::Utc;
use hex;
use humantime::parse_duration;
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
    io::{BufRead, BufReader, Read},
    str,
    str::FromStr,
    sync::Arc,
    time::Duration,
};
use tracing::{debug, span, warn, Level};
use warp::{body::FullBody, http::StatusCode, Buf};

// TODO use tokio-fs

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

        match regex_key.captures(file) {
            Some(capture) => match capture.name("key") {
                Some(x) => Ok(x.as_str().to_string()),
                _ => Err(Error::InvalidHeader),
            },
            None => Err(Error::InvalidHeader),
        }
    }
}

fn validate_signature(
    file: &[u8],
    pubkey: PKey<Public>,
    hash_type: HashType,
    digest: &[u8],
) -> Result<bool, ErrorStack> {
    let mut verifier = Verifier::new(hash_type.to_openssl_hash(), &pubkey)?;
    verifier.update(file)?;
    verifier.verify(digest)
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
        match self.ttl.parse::<u64>() {
            // No units -> seconds
            Ok(s) => Ok(Duration::from_secs(s)),
            // Else parse as human time
            Err(_) => parse_duration(&self.ttl).map_err(|e| e.into()),
        }
    }
}

pub fn put(
    target_id: String,
    source_id: String,
    file_id: String,
    params: SharedFilesPutParams,
    job_config: Arc<JobConfig>,
    body: FullBody,
) -> Result<StatusCode, Error> {
    let span = span!(
        Level::INFO,
        "shared_files_put",
        target_id = %target_id,
        source_id = %source_id,
        file_id = %file_id,
    );
    let _enter = span.enter();

    if !job_config
        .nodes
        .read()
        .expect("Cannot read nodes list")
        .is_subnode(&source_id)
    {
        warn!("unknown source {}", source_id);
        return Ok(StatusCode::NOT_FOUND);
    }

    let mut stream = BufReader::new(body.into_buf().reader());
    let mut raw_meta = String::new();
    // Here we cannot iterate on lines as the file content may not be valid UTF-8.
    let mut read = 2;
    // Let's read while we find an empty line.
    while read > 1 {
        read = stream.read_line(&mut raw_meta)?;
    }
    let meta = Metadata::from_str(&raw_meta)?;
    let mut file = vec![];
    stream.read_to_end(&mut file)?;

    let base_path = job_config
        .cfg
        .shared_files
        .path
        .join(&target_id)
        .join(&source_id);
    let pubkey = get_pubkey(&meta.short_pubkey)?;

    let key_hash = meta.algorithm.hash(&pubkey.public_key_to_der()?);
    if key_hash != meta.digest {
        warn!(
            "hash of public key ({}) does not match metadata ({})",
            key_hash, meta.digest
        );
        return Ok(StatusCode::NOT_FOUND);
    }

    match validate_signature(
        &file,
        pubkey,
        meta.algorithm,
        &hex::decode(&meta.digest).unwrap(),
    ) {
        Ok(is_valid) => {
            if !is_valid {
                warn!("invalid signature");
                return Ok(StatusCode::INTERNAL_SERVER_ERROR);
            }
        }
        Err(e) => {
            warn!("error checking file signature: {}", e);
            return Ok(StatusCode::INTERNAL_SERVER_ERROR);
        }
    }

    // Everything is correct, let's store the file
    fs::create_dir_all(&base_path)?;
    fs::write(
        &base_path.join(format!("{}.metadata", file_id)),
        format!(
            "{}expires={}\n",
            meta,
            match params.ttl() {
                // Removal timestamp = now + ttl
                Ok(ttl) =>
                    Utc::now()
                        + chrono::Duration::from_std(ttl).expect("Unexpectedly large duration"),
                Err(e) => {
                    warn!("invalid ttl: {}", e);
                    return Ok(StatusCode::INTERNAL_SERVER_ERROR);
                }
            }
        ),
    )?;
    fs::write(&base_path.join(file_id), file)?;
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
    let span = span!(
        Level::INFO,
        "shared_files_head",
        target_id = %target_id,
        source_id = %source_id,
        file_id = %file_id,
    );
    let _enter = span.enter();

    let file_path = job_config
        .cfg
        .shared_files
        .path
        .join(&target_id)
        .join(&source_id)
        .join(format!("{}.metadata", file_id));

    if !file_path.exists() {
        debug!("file {} does not exist", file_path.display());
        return Ok(StatusCode::NOT_FOUND);
    }

    let metadata_hash = Metadata::parse_value("hash_value", &fs::read_to_string(&file_path)?)?;

    Ok(if metadata_hash == params.hash {
        debug!(
            "file {} has given hash '{}'",
            file_path.display(),
            metadata_hash
        );
        StatusCode::OK
    } else {
        debug!(
            "file {} has '{}' hash but given hash is '{}'",
            file_path.display(),
            metadata_hash,
            params.hash,
        );
        StatusCode::NOT_FOUND
    })
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
            Duration::from_secs(3600 + 7)
        );
        assert_eq!(
            SharedFilesPutParams::new("1hour 2minutes").ttl().unwrap(),
            Duration::from_secs(3600 + 120)
        );
        assert_eq!(
            SharedFilesPutParams::new("1d 7seconds").ttl().unwrap(),
            Duration::from_secs(86400 + 7)
        );
        assert_eq!(
            SharedFilesPutParams::new("9136").ttl().unwrap(),
            Duration::from_secs(9136)
        );
        assert!(SharedFilesPutParams::new("913j").ttl().is_err());
        assert!(SharedFilesPutParams::new("913b83").ttl().is_err());
        assert!(SharedFilesPutParams::new("913h 89j").ttl().is_err());
    }
}
