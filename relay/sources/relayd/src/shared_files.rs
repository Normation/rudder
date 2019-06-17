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

extern crate data_encoding;
extern crate ring;
use std::collections::HashMap;

use regex::Regex;
use std::fs;
use std::path::Path;
extern crate chrono;
use chrono::prelude::DateTime;
use chrono::{Duration as OldDuration, Utc};
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use crate::error::Error;
use hyper::StatusCode;
use std::fs::File;
use std::io::{BufReader, Read, Write};
use std::str::FromStr;
pub extern crate sha1;

use sha1::Sha1;
use std::env;
extern crate sha2;
use sha2::{Digest, Sha256, Sha512};

use crate::data::node::NodesList;

#[derive(Debug)]
pub struct Metadata {
    header: String,
    algorithm: String,
    digest: String,
    hash_value: String,
    short_pubkey: String,
    hostname: String,
    keydate: String,
    keyid: String,
    expires: String,
}

impl FromStr for Metadata {
    type Err = Error;

    fn from_str(s: &str) -> Result<Self, Self::Err> {
        let metadata: Vec<&str> = s.splitn(18, |c| c == '=' || c == '\n').collect();

        Ok(Metadata {
            header: metadata[1].to_string(),
            algorithm: metadata[3].to_string(),
            digest: metadata[5].to_string(),
            hash_value: metadata[7].to_string(),
            short_pubkey: metadata[9].to_string(),
            hostname: metadata[11].to_string(),
            keydate: metadata[13].to_string(),
            keyid: metadata[15].to_string(),
            expires: metadata[17].to_string(),
        })
    }
}

pub fn same_hash_than_in_nodeslist(hash_value: &str, source_uuid: String) -> bool {
    let nodeslist = NodesList::new("root".to_string(), "tests/files/nodeslist.json", None);

    let myvec2: Vec<String> = nodeslist
        .unwrap()
        .get_keyhash_from_uuid(&source_uuid)
        .unwrap()
        .split(':')
        .map(|s| s.to_string())
        .collect();

    let (algorithm, keyhash) = (&myvec2[0], &myvec2[1]);

    hash_value == keyhash.to_string()
}

pub fn parse_ttl(ttl: String) -> String {
    let regex_numbers = Regex::new(r"^(?:(?P<days>\d+)(?:d|days))?\s*(?:(?P<hours>\d+)(?:h|hours))?\s*(?:(?P<minutes>\d+)(?:m|minutes))?\s*(?:(?P<seconds>\d+)(?:s|seconds))").unwrap();

    let mut seconds: u64 = 0;
    let iter_vec = vec!["seconds", "minutes", "hours", "days"];

    for elt in iter_vec {
        match regex_numbers.captures(&ttl) {
            Some(y) => match y.name(elt) {
                Some(x) => {
                    seconds += dhms_to_seconds(x.as_str().parse::<u64>().unwrap(), elt).unwrap()
                }
                _ => seconds += 0,
            },
            None => (),
        }
    }
    DateTime::<Utc>::from(
        Utc::now()
            .checked_add_signed(OldDuration::from_std(Duration::from_secs(seconds)).unwrap())
            .unwrap(),
    )
    .timestamp()
    .to_string()
}

pub fn dhms_to_seconds(number: u64, dhms: &str) -> Result<u64, ()> {
    match dhms {
        "d" | "days" => Ok(number * 86400),
        "h" | "hours" => Ok(number * 3600),
        "m" | "minutes" => Ok(number * 60),
        "s" | "seconds" => Ok(number),
        _ => Err(()),
    }
}

pub fn metadata_writer(hashmap: HashMap<String, String>, peek: warp::filters::path::Peek) -> String {
    let myvec: Vec<String> = peek.as_str().split('/').map(|s| s.to_string()).collect();
    let (target_uuid, source_uuid, file_id) = (&myvec[0], &myvec[1], &myvec[2]);

    let mut mystring = String::new();

    if !same_hash_than_in_nodeslist(hashmap.get("hash_value").unwrap(), source_uuid.to_string())
    {
        return "wrong hash".to_string();
    }

    let iter_vec = vec![
        "header",
        "algorithm",
        "digest",
        "hash_value",
        "short_pubkey",
        "hostname",
        "keydate",
        "keyid",
        "ttl",
    ];

    for elt in iter_vec {
        if elt == "ttl" {
            mystring.push_str(&format!(
                "expires={}\n",
                parse_ttl(hashmap.get("ttl").unwrap().to_string())
            ));
        } else {
            mystring.push_str(&format!("{}={}\n", elt, hashmap.get(elt).unwrap()));
        }
    }
    fs::create_dir_all(format!("./{}/{}/", target_uuid, source_uuid)); // on cree les folders s'ils existent pas
    //fs::create_dir_all(format!("/var/rudder/configuration-repository/shared-files/{}/{}/", target_uuid, source_uuid)); // real path
    fs::write(format!("./{}", peek.as_str()), mystring).expect("Unable to write file");

    "perfect".to_string()
}

pub fn metadata_hash_checker(filename: String, hash: String) -> hyper::StatusCode {
    if !Path::new(&filename).exists() {
        return StatusCode::from_u16(404).unwrap();
    }

    let contents = fs::read_to_string(&filename).expect("Something went wrong reading the file");

    let re = Regex::new(r"hash_value=([a-f0-9]+)\n").expect("unable to parse hash regex");

    if re
        .captures_iter(&contents)
        .next()
        .and_then(|s| s.get(1))
        .map(|s| s.as_str())
        == Some(&hash)
    {
        return StatusCode::from_u16(200).unwrap();
    }

    StatusCode::from_u16(404).unwrap()
}

pub fn parse_hash_from_raw(raw: String) -> String {
    raw.split('=')
        .map(|s| s.to_string())
        .filter(|s| s != "hash")
        .collect::<String>()
}

pub fn parse_path_from_peek(peek: warp::filters::path::Peek) -> String {
    peek.as_str().split('/').map(|s| s.to_string()).collect()
}

pub fn get_pubkey(metadata: Metadata) -> String {
    format!(
        "-----BEGIN RSA PRIVATE KEY-----\n{}\n-----END RSA PRIVATE KEY-----\n",
        metadata.short_pubkey
    )
}

// # Validate that a given public key matches the provided hash
// # The public key is a key object and the hash is of the form 'algorithm:kex_value'
// def validate_key(pubkey, keyhash):
//   try:
//     (keyhash_type, keyhash_value) = keyhash.split(":",1)
//   except:
//     raise ValueError("ERROR invalid key hash, it should be 'type:value': " + keyhash)
//   pubkey_bin = pubkey.exportKey(format="DER")
//   h = get_hash(keyhash_type, pubkey_bin)
//   return h.hexdigest() == keyhash_value

// # Validate that a message has been properly signed by the given key
// # The public key is a key object, algorithm is the hash algorithm and digest the hex signature
// # The key algorithm will always be RSA because is is loaded as such
// # Returns a booleas for the validity and the message hash to avoid computing it twice
// def validate_message(message, pubkey, algorithm, digest):
//   h = get_hash(algorithm, message)
//   cipher = PKCS1_v1_5.new(pubkey)
//   return (cipher.verify(h, toBin(digest)), h.hexdigest())
