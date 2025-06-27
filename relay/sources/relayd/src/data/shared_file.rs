// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

use std::{collections::HashMap, fmt, path::PathBuf, str, str::FromStr};

use anyhow::Error;
use openssl::{
    error::ErrorStack,
    pkey::{PKey, Public},
    rsa::Rsa,
    sign::Verifier,
};
use regex::Regex;

use crate::{
    error::RudderError,
    hashing::{Hash, HashType},
};

#[derive(Debug, Copy, Clone, PartialEq, Eq)]
pub enum SignatureFormat {
    // "rudder-signature-v1"
    RudderV1,
}

impl FromStr for SignatureFormat {
    type Err = Error;

    fn from_str(s: &str) -> Result<Self, Self::Err> {
        match s {
            "rudder-signature-v1" => Ok(Self::RudderV1),
            _ => Err(RudderError::InvalidHeader(s.to_string()).into()),
        }
    }
}

impl fmt::Display for SignatureFormat {
    fn fmt(&self, f: &mut fmt::Formatter) -> fmt::Result {
        write!(
            f,
            "{}",
            match self {
                SignatureFormat::RudderV1 => "rudder-signature-v1",
            }
        )
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SharedFile {
    pub source_id: String,
    pub target_id: String,
    pub file_id: String,
}

impl SharedFile {
    pub fn new(source_id: String, target_id: String, file_id: String) -> Result<SharedFile, Error> {
        // Validate data
        // Only ascii alphanumeric, - and .
        // This is the documented constraint for file_id
        // More than enough for node ids too but we don't have a precise spec
        let check = Regex::new(r"^[A-Za-z0-9\-_.]+$").unwrap();
        if !check.is_match(&source_id) {
            return Err(
                RudderError::InvalidSharedFile(format!("invalid source_id: {source_id}",)).into(),
            );
        }
        if !check.is_match(&target_id) {
            return Err(
                RudderError::InvalidSharedFile(format!("invalid target_id: {target_id}",)).into(),
            );
        }
        if !check.is_match(&file_id) {
            return Err(
                RudderError::InvalidSharedFile(format!("invalid file_id: {file_id}")).into(),
            );
        }
        Ok(SharedFile {
            source_id,
            target_id,
            file_id,
        })
    }

    pub fn path(&self) -> PathBuf {
        PathBuf::from(&self.target_id)
            .join(&self.source_id)
            .join(&self.file_id)
    }

    pub fn url(&self) -> String {
        format!("{}/{}/{}", &self.target_id, &self.source_id, &self.file_id)
    }
}

/// Metadata of a shared file.
/// Covers metadata sent as file headers
/// or stored as local metadata.
///
/// Uses 1.1 metadata version at least (1.0 was used for old inventories)
/// Represented as key-value pairs
#[derive(Debug, PartialEq, Eq)]
pub struct Metadata {
    format: SignatureFormat,
    pub digest: String,
    pub hash: Hash,
    // TODO Use proper public key type
    // Currently exposed though the `pubkey` method
    short_pubkey: String,
    // ttl may not be there in all contexts
    pub expires: Option<i64>,
    // These fields are currently not used
    hostname: String,
    key_date: String,
    key_id: String,
}

impl fmt::Display for Metadata {
    fn fmt(&self, f: &mut fmt::Formatter) -> fmt::Result {
        writeln!(f, "header={}", &self.format)?;
        writeln!(f, "algorithm={}", &self.hash.hash_type)?;
        writeln!(f, "digest={}", &self.digest)?;
        writeln!(f, "hash_value={}", &self.hash.hex())?;
        writeln!(f, "short_pubkey={}", &self.short_pubkey)?;
        writeln!(f, "hostname={}", &self.hostname)?;
        writeln!(f, "keydate={}", &self.key_date)?;
        writeln!(f, "keyid={}", &self.key_id)?;
        if let Some(expires) = self.expires {
            writeln!(f, "expires={expires}")?;
        }
        Ok(())
    }
}

impl FromStr for Metadata {
    type Err = Error;

    fn from_str(s: &str) -> Result<Self, Self::Err> {
        // Iterate over lines, into hashmap
        let mut parsed: HashMap<&str, &str> = HashMap::new();
        for line in s.lines() {
            let kv: Vec<&str> = line.splitn(2, '=').collect();
            if kv.len() == 2 && parsed.insert(kv[0], kv[1]).is_some() {
                let e: Error = RudderError::DuplicateHeader(line.to_string()).into();
                return Err(e);
            }
        }

        fn extract<'a>(
            headers: &HashMap<&'a str, &'a str>,
            key: &'a str,
        ) -> Result<&'a str, Error> {
            headers
                .get(key)
                .copied()
                .ok_or_else(|| RudderError::MissingHeader(key.to_string()).into())
        }

        let format = extract(&parsed, "header")?.parse::<SignatureFormat>()?;
        let hash = Hash::new(
            extract(&parsed, "algorithm")?,
            extract(&parsed, "hash_value")?,
        )?;

        let digest = extract(&parsed, "digest")?.to_string();
        // Validate hexadecimal string
        hex::decode(&digest).map_err(|_| RudderError::InvalidHeader(digest.clone()))?;

        let short_pubkey = extract(&parsed, "short_pubkey")?.to_string();
        // validate public key
        Self::parse_pubkey(&short_pubkey)?;

        let hostname = extract(&parsed, "hostname")?.to_string();
        let key_date = extract(&parsed, "keydate")?.to_string();
        let key_id = extract(&parsed, "keyid")?.to_string();

        let expires = match parsed.get("expires") {
            Some(ttl) => Some(ttl.parse::<i64>()?),
            None => None,
        };

        Ok(Metadata {
            format,
            digest,
            hash,
            short_pubkey,
            hostname,
            key_date,
            key_id,
            expires,
        })
    }
}

impl Metadata {
    fn parse_pubkey(short_key: &str) -> Result<PKey<Public>, ErrorStack> {
        PKey::from_rsa(Rsa::public_key_from_pem_pkcs1(
            format!("-----BEGIN RSA PUBLIC KEY-----\n{short_key}\n-----END RSA PUBLIC KEY-----\n",)
                .as_bytes(),
        )?)
    }

    /// Get public key from metadata
    pub fn pubkey(&self) -> Result<PKey<Public>, ErrorStack> {
        Self::parse_pubkey(&self.short_pubkey)
    }

    fn validate_signature_key(
        pubkey: PKey<Public>,
        data: &[u8],
        hash_type: HashType,
        digest: &[u8],
    ) -> Result<bool, ErrorStack> {
        let mut verifier = Verifier::new(hash_type.to_openssl_hash(), &pubkey)?;
        verifier.update(data)?;
        verifier.verify(digest)
    }

    pub fn validate_signature(
        &self,
        data: &[u8],
        hash_type: HashType,
        digest: &[u8],
    ) -> Result<bool, ErrorStack> {
        Self::validate_signature_key(self.pubkey()?, data, hash_type, digest)
    }
}

#[cfg(test)]
mod tests {
    use openssl::sign::Signer;

    use super::*;

    #[test]
    fn it_checks_shared_file() {
        assert_eq!(
            SharedFile::new(
                "source".to_string(),
                "target".to_string(),
                "file".to_string(),
            )
            .unwrap(),
            SharedFile {
                source_id: "source".to_string(),
                target_id: "target".to_string(),
                file_id: "file".to_string(),
            }
        );
        assert!(SharedFile::new(
            "source".to_string(),
            "target".to_string(),
            "file/../passwd".to_string(),
        )
        .is_err());
    }

    #[test]
    fn it_writes_and_parses_the_metadata() {
        let metadata = Metadata {
            format: SignatureFormat::RudderV1,
            hash: Hash::new(
                "sha256",
                "a75fda39a7af33eb93ab1c74874dcf66d5761ad30977368cf0c4788cf5bfd34f",
            )
            .unwrap(),
            digest: "8ca9efc5752e133e2e80e2661c176fa50f".to_string(),
            short_pubkey: "MIICCgKCAgEAuok8JTvRssiupO0IfH4OGnWFqQg5dmI/4JsCiPEUf78iFBwFFpwuNXDJXCKaHtpjuc3DAy9l7fmZ+bQmkfde+Qo3yAd2ZsId80TBZOy6uFQyl4ASLNgY8RKIFxD6+AsutI27KexSnL3QLCgywnheRv4Ur31a6MVY1xfSQnADruBBad+5SaF3hTpEcAMg2hDQsIcyR32MPRy9MOVmvBlgI2hZsgh9QQf9wTLxGuMw/pJKOPRwwFkk/5bhFBve2sL1OI0pRsM6i7SxNXRhM6NWlmObhP+Z7C6N7TY00Z+tizgETmYJ35llyInjc1i+0bWaj5p3cbSCVdQ5zomZ3L9XbsWmjl0P/cw06qqNPuLR799K+R1XgA94nUUzo2pVigPh6sj2XMS8FOWXMXy2TNEOA+NQV5+vYwIlUizvB/HHSc3WKqNGgCifdJBmJJ8QTg5cJE6s+91O99eMMAQ0Ecj+nY5QEYkbIn4gjNpojam3jyS72o0J4nlj4ECbR/rj6L5b+kj5F3DbYqSdLC+crKUIoBZH1msCuJcQ9Zk/YHw87iVyWoZOVtJUUaw3n8vH/YCWPBQRzZp+4zlyIYJIIz+V/FJZX5YNW9XgoeRG8Q0mOmLy0FbQUS/klYlpeW3PKLSQmcSLvrgZnhKMyhEohC0zOSqJU0ui4VUWY5tv1bhbTo8CAwEAAQ==".to_string(),
            hostname: "ubuntu-18-04-64".to_string(),
            key_date: "2018-10-3118:21:43.653257143".to_string(),
            key_id: "B29D02BB".to_string(),
            expires: None,
        };
        let serialized = "header=rudder-signature-v1\nalgorithm=sha256\ndigest=8ca9efc5752e133e2e80e2661c176fa50f\nhash_value=a75fda39a7af33eb93ab1c74874dcf66d5761ad30977368cf0c4788cf5bfd34f\nshort_pubkey=MIICCgKCAgEAuok8JTvRssiupO0IfH4OGnWFqQg5dmI/4JsCiPEUf78iFBwFFpwuNXDJXCKaHtpjuc3DAy9l7fmZ+bQmkfde+Qo3yAd2ZsId80TBZOy6uFQyl4ASLNgY8RKIFxD6+AsutI27KexSnL3QLCgywnheRv4Ur31a6MVY1xfSQnADruBBad+5SaF3hTpEcAMg2hDQsIcyR32MPRy9MOVmvBlgI2hZsgh9QQf9wTLxGuMw/pJKOPRwwFkk/5bhFBve2sL1OI0pRsM6i7SxNXRhM6NWlmObhP+Z7C6N7TY00Z+tizgETmYJ35llyInjc1i+0bWaj5p3cbSCVdQ5zomZ3L9XbsWmjl0P/cw06qqNPuLR799K+R1XgA94nUUzo2pVigPh6sj2XMS8FOWXMXy2TNEOA+NQV5+vYwIlUizvB/HHSc3WKqNGgCifdJBmJJ8QTg5cJE6s+91O99eMMAQ0Ecj+nY5QEYkbIn4gjNpojam3jyS72o0J4nlj4ECbR/rj6L5b+kj5F3DbYqSdLC+crKUIoBZH1msCuJcQ9Zk/YHw87iVyWoZOVtJUUaw3n8vH/YCWPBQRzZp+4zlyIYJIIz+V/FJZX5YNW9XgoeRG8Q0mOmLy0FbQUS/klYlpeW3PKLSQmcSLvrgZnhKMyhEohC0zOSqJU0ui4VUWY5tv1bhbTo8CAwEAAQ==\nhostname=ubuntu-18-04-64\nkeydate=2018-10-3118:21:43.653257143\nkeyid=B29D02BB\n";

        assert_eq!(metadata.to_string(), serialized);
        assert_eq!(metadata, serialized.parse().unwrap());
    }

    #[test]
    fn it_writes_and_parses_the_metadata_with_expired() {
        let metadata = Metadata {
            format: SignatureFormat::RudderV1,
            hash: Hash::new(
                "sha256",
                "a75fda39a7af33eb93ab1c74874dcf66d5761ad30977368cf0c4788cf5bfd34f",
            )
            .unwrap(),
            digest: "8ca9efc5752e133e2e80e2661c176fa50f".to_string(),
            short_pubkey: "MIICCgKCAgEAuok8JTvRssiupO0IfH4OGnWFqQg5dmI/4JsCiPEUf78iFBwFFpwuNXDJXCKaHtpjuc3DAy9l7fmZ+bQmkfde+Qo3yAd2ZsId80TBZOy6uFQyl4ASLNgY8RKIFxD6+AsutI27KexSnL3QLCgywnheRv4Ur31a6MVY1xfSQnADruBBad+5SaF3hTpEcAMg2hDQsIcyR32MPRy9MOVmvBlgI2hZsgh9QQf9wTLxGuMw/pJKOPRwwFkk/5bhFBve2sL1OI0pRsM6i7SxNXRhM6NWlmObhP+Z7C6N7TY00Z+tizgETmYJ35llyInjc1i+0bWaj5p3cbSCVdQ5zomZ3L9XbsWmjl0P/cw06qqNPuLR799K+R1XgA94nUUzo2pVigPh6sj2XMS8FOWXMXy2TNEOA+NQV5+vYwIlUizvB/HHSc3WKqNGgCifdJBmJJ8QTg5cJE6s+91O99eMMAQ0Ecj+nY5QEYkbIn4gjNpojam3jyS72o0J4nlj4ECbR/rj6L5b+kj5F3DbYqSdLC+crKUIoBZH1msCuJcQ9Zk/YHw87iVyWoZOVtJUUaw3n8vH/YCWPBQRzZp+4zlyIYJIIz+V/FJZX5YNW9XgoeRG8Q0mOmLy0FbQUS/klYlpeW3PKLSQmcSLvrgZnhKMyhEohC0zOSqJU0ui4VUWY5tv1bhbTo8CAwEAAQ==".to_string(),
            hostname: "ubuntu-18-04-64".to_string(),
            key_date: "2018-10-3118:21:43.653257143".to_string(),
            key_id: "B29D02BB".to_string(),
            expires: Some(1_580_941_341),
        };
        let serialized = "header=rudder-signature-v1\nalgorithm=sha256\ndigest=8ca9efc5752e133e2e80e2661c176fa50f\nhash_value=a75fda39a7af33eb93ab1c74874dcf66d5761ad30977368cf0c4788cf5bfd34f\nshort_pubkey=MIICCgKCAgEAuok8JTvRssiupO0IfH4OGnWFqQg5dmI/4JsCiPEUf78iFBwFFpwuNXDJXCKaHtpjuc3DAy9l7fmZ+bQmkfde+Qo3yAd2ZsId80TBZOy6uFQyl4ASLNgY8RKIFxD6+AsutI27KexSnL3QLCgywnheRv4Ur31a6MVY1xfSQnADruBBad+5SaF3hTpEcAMg2hDQsIcyR32MPRy9MOVmvBlgI2hZsgh9QQf9wTLxGuMw/pJKOPRwwFkk/5bhFBve2sL1OI0pRsM6i7SxNXRhM6NWlmObhP+Z7C6N7TY00Z+tizgETmYJ35llyInjc1i+0bWaj5p3cbSCVdQ5zomZ3L9XbsWmjl0P/cw06qqNPuLR799K+R1XgA94nUUzo2pVigPh6sj2XMS8FOWXMXy2TNEOA+NQV5+vYwIlUizvB/HHSc3WKqNGgCifdJBmJJ8QTg5cJE6s+91O99eMMAQ0Ecj+nY5QEYkbIn4gjNpojam3jyS72o0J4nlj4ECbR/rj6L5b+kj5F3DbYqSdLC+crKUIoBZH1msCuJcQ9Zk/YHw87iVyWoZOVtJUUaw3n8vH/YCWPBQRzZp+4zlyIYJIIz+V/FJZX5YNW9XgoeRG8Q0mOmLy0FbQUS/klYlpeW3PKLSQmcSLvrgZnhKMyhEohC0zOSqJU0ui4VUWY5tv1bhbTo8CAwEAAQ==\nhostname=ubuntu-18-04-64\nkeydate=2018-10-3118:21:43.653257143\nkeyid=B29D02BB\nexpires=1580941341\n";

        assert_eq!(metadata.to_string(), serialized);
        assert_eq!(metadata, serialized.parse().unwrap());
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

        assert!(
            Metadata::validate_signature_key(keypub, data, HashType::Sha512, &signature).unwrap()
        );
    }
}
