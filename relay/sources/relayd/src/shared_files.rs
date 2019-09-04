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

use crate::{error::Error, JobConfig};
use chrono::{prelude::DateTime, Duration, Utc};
use hex;
use openssl::{
    error::ErrorStack,
    hash::MessageDigest,
    pkey::{PKey, Public},
    rsa::Rsa,
    sign::Verifier,
};
use regex::Regex;
use sha2::{Digest, Sha256, Sha512};
use std::{
    fmt, fs,
    io::{BufRead, Read},
    path::Path,
    str,
    str::FromStr,
    sync::Arc,
};
use warp::{http::StatusCode, Buf};
#[derive(Debug, Clone, Copy)]
pub enum HashType {
    Sha256,
    Sha512,
}

impl FromStr for HashType {
    type Err = Error;

    fn from_str(s: &str) -> Result<Self, Self::Err> {
        match s {
            "sha256" => Ok(HashType::Sha256),
            "sha512" => Ok(HashType::Sha512),
            _ => Err(Error::InvalidHashType),
        }
    }
}

impl fmt::Display for HashType {
    fn fmt(&self, f: &mut fmt::Formatter) -> fmt::Result {
        write!(
            f,
            "{}",
            match self {
                HashType::Sha256 => "sha256",
                HashType::Sha512 => "sha512",
            }
        )
    }
}

impl HashType {
    pub fn hash(&self, bytes: &[u8]) -> String {
        match &self {
            HashType::Sha256 => {
                let mut hasher = Sha256::new();
                hasher.input(bytes);
                format!("{:x}", hasher.result())
            }
            HashType::Sha512 => {
                let mut hasher = Sha512::new();
                hasher.input(bytes);
                format!("{:x}", hasher.result())
            }
        }
    }

    fn to_openssl_hash(&self) -> MessageDigest {
        match &self {
            HashType::Sha256 => MessageDigest::sha256(),
            HashType::Sha512 => MessageDigest::sha512(),
        }
    }
}

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
        if parse_value("header", s).unwrap() != "rudder-signature-v1" {
            return Err(Error::InvalidHeader);
        }
        Ok(Metadata {
            header: parse_value("header", s)?,
            algorithm: HashType::from_str(s)?,
            digest: parse_value("digest", s)?,
            hash_value: parse_value("hash_value", s)?,
            short_pubkey: parse_value("short_pubkey", s)?,
            hostname: parse_value("hostname", s)?,
            key_date: parse_value("keydate", s)?,
            key_id: parse_value("keyid", s)?,
        })
    }
}

pub fn parse_value(key: &str, file: &str) -> Result<String, Error> {
    let regex_key = Regex::new(&format!(r"{}=(?P<key>[^\n]+)\n", key)).unwrap();

    match regex_key.captures(&file) {
        Some(capture) => match capture.name("key") {
            Some(x) => Ok(x.as_str().to_string()),
            _ => Err(Error::InvalidHeader),
        },
        None => Err(Error::InvalidHeader),
    }
}

pub fn same_hash_than_in_nodeslist(
    pubkey: PKey<Public>,
    hash_type: HashType,
    key_hash: &str,
) -> Result<bool, Error> {
    Ok(hash_type.hash(&pubkey.public_key_to_der()?) == key_hash)
}

pub fn get_pubkey(pubkey: String) -> Result<PKey<Public>, ErrorStack> {
    PKey::from_rsa(Rsa::public_key_from_pem_pkcs1(
        format!(
            "-----BEGIN RSA PUBLIC KEY-----\n{}\n-----END RSA PUBLIC KEY-----\n",
            pubkey
        )
        .as_bytes(),
    )?)
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

pub fn parse_ttl(ttl: String) -> Result<i64, Error> {
    let regex_timestamp = Regex::new(r"^([0-9]+)$").unwrap();

    if regex_timestamp.is_match(&ttl) {
        return Ok(ttl.parse::<i64>().unwrap());
    };

    let regex_numbers = Regex::new(r"^(?:(?P<days>\d+)(?:d|days|day))?\s*(?:(?P<hours>\d+)(?:h|hours|hour))?\s*(?:(?P<minutes>\d+)(?:m|minutes|minute))?\s*(?:(?P<seconds>\d+)(?:s|seconds|second))?$").unwrap();

    fn parse_time<'t>(cap: &regex::Captures<'t>, n: &str) -> Result<i64, Error> {
        Ok(match cap.name(n) {
            Some(s) => s.as_str().parse::<i64>()?,
            None => 0,
        })
    }

    if let Some(cap) = regex_numbers.captures(&ttl) {
        let s = parse_time(&cap, "seconds")?;
        let m = parse_time(&cap, "minutes")?;
        let h = parse_time(&cap, "hours")?;
        let d = parse_time(&cap, "days")?;

        let expiration: DateTime<Utc> = Utc::now()
            + Duration::seconds(s)
            + Duration::seconds(m)
            + Duration::seconds(h)
            + Duration::seconds(d);

        Ok(expiration.timestamp())
    } else {
        Err(Error::InvalidTtl(ttl))
    }
}

pub fn metadata_parser(buf: &mut warp::body::FullBody) -> Result<Metadata, Error> {
    let mut metadata: Vec<u8> = Vec::new();
    let _ = buf.reader().read_to_end(&mut metadata)?;
    Metadata::from_str(str::from_utf8(&metadata)?)
}

pub fn file_writer(
    buf: &mut warp::body::FullBody,
    path: &Path,
    job_config: Arc<JobConfig>,
) -> Result<(), Error> {
    let mut file_content: Vec<u8> = vec![];

    buf.by_ref().reader().consume(1); // skip the line feed
    buf.reader().read_to_end(&mut file_content).unwrap();

    Ok(fs::write(
        job_config.cfg.shared_files.path.join(path),
        file_content,
    )?)
}

pub fn put_handler(
    metadata_string: String,
    peek: &str,
    ttl: String,
    job_config: Arc<JobConfig>,
    mut buf: warp::body::FullBody,
) -> Result<StatusCode, Error> {
    let timestamp = match parse_ttl(ttl) {
        Ok(ttl) => ttl,
        Err(_x) => return Ok(StatusCode::from_u16(500).unwrap()),
    };

    let myvec: Vec<String> = peek.split('/').map(|s| s.to_string()).collect();
    let (target_uuid, source_uuid, _file_id) = (&myvec[0], &myvec[1], &myvec[2]);

    let meta = Metadata::from_str(&metadata_string).unwrap();
    let (file, meta_pubkey, hash_type, digest) = (
        buf.by_ref(),
        &meta.short_pubkey,
        meta.algorithm,
        &meta.digest,
    );

    if !same_hash_than_in_nodeslist(
        get_pubkey(meta_pubkey.to_string()).unwrap(),
        hash_type,
        &meta.digest,
    )? {
        return Ok(StatusCode::from_u16(404).unwrap());
    }

    if validate_signature(
        file.bytes(),
        get_pubkey(meta_pubkey.to_string()).unwrap(),
        hash_type,
        &hex::decode(digest).unwrap(),
    )? {
        return Ok(StatusCode::from_u16(500).unwrap());
    }

    if !job_config
        .nodes
        .read()
        .expect("Cannot read nodes list")
        .is_subnode(source_uuid)
    {
        return Ok(StatusCode::from_u16(404).unwrap());
    }

    fs::create_dir_all(
        job_config
            .cfg
            .shared_files
            .path
            .join(target_uuid)
            .join(source_uuid),
    )
    .unwrap(); // create folders if they don't exist
    fs::write(
        job_config
            .cfg
            .shared_files
            .path
            .join(format!("{}.metadata", peek)),
        format!("{}expires={}\n", metadata_string, timestamp),
    )
    .expect("Unable to write file");
    file_writer(buf.by_ref(), Path::new(peek), job_config.clone())?;
    Ok(StatusCode::from_u16(200).unwrap())
}

pub fn metadata_hash_checker(path: String, hash: String, job_config: Arc<JobConfig>) -> StatusCode {
    if !job_config
        .cfg
        .shared_files
        .path
        .join(format!("{}.metadata", path))
        .exists()
    {
        return StatusCode::from_u16(404).unwrap();
    }

    let contents = fs::read_to_string(
        job_config
            .cfg
            .shared_files
            .path
            .join(format!("{}.metadata", path)),
    )
    .unwrap();

    if parse_value("hash_value", &contents).unwrap() == hash {
        return StatusCode::from_u16(200).unwrap();
    }

    StatusCode::from_u16(404).unwrap()
}

pub fn parse_parameter_from_raw(raw: String) -> String {
    raw.split('=')
        .map(|s| s.to_string())
        .filter(|s| s != "hash" && s != "ttl" && s != "hash_type")
        .collect::<String>()
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
    pub fn it_validates_keyhashes() {
        assert_eq!(same_hash_than_in_nodeslist(
        get_pubkey("MIIBCAKCAQEAlntroa72gD50MehPoyp6mRS5fzZpsZEHu42vq9KKxbqSsjfUmxnT
Rsi8CDvBt7DApIc7W1g0eJ6AsOfV7CEh3ooiyL/fC9SGATyDg5TjYPJZn3MPUktg
YBzTd1MMyZL6zcLmIpQBH6XHkH7Do/RxFRtaSyicLxiO3H3wapH20TnkUvEpV5Qh
zUkNM8vHZuu3m1FgLrK5NCN7BtoGWgeyVJvBMbWww5hS15IkCRuBkAOK/+h8xe2f
hMQjrt9gW2qJpxZyFoPuMsWFIaX4wrN7Y8ZiN37U2q1G11tv2oQlJTQeiYaUnTX4
z5VEb9yx2KikbWyChM1Akp82AV5BzqE80QIBIw==".to_string()).unwrap(),
        HashType::Sha512,
        "3c4641f2e99ade126b9923ffdfc432d486043e11ce7bd5528cc50ed372fc4c224dba7f2a2a3a24f114c06e42af5f45f5c248abd7ae300eeefc27bcf0687d7040"
    ).unwrap(), true);
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
        assert!(parse_ttl("1h 7s".to_string()).is_ok());
        assert!(parse_ttl("1hour 2minutes".to_string()).is_ok());
        assert!(parse_ttl("1d 7seconds".to_string()).is_ok());
        assert_eq!(parse_ttl("9136".to_string()).unwrap(), 9136);
        assert!(parse_ttl("913j".to_string()).is_err());
        assert!(parse_ttl("913b83".to_string()).is_err());
        assert!(parse_ttl("913h 89j".to_string()).is_err());
    }
}
