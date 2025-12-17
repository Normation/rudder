use anyhow::{Context, Result, bail};
use ini::{Ini, ParseOption};
use serde_json::{Map, Value};
use std::fs::{File, read_to_string};
use std::io::{Read, Write};
use std::path::Path;
use std::process::{Command, Stdio};
use tempdir::TempDir;
use utf16string::{LittleEndian, WString};

pub fn run(data: Map<String, Value>, audit: bool) -> Result<()> {
    if audit {
        unimplemented!("Audit mode not yet implemented!");
    } else {
        let tmp_dir = TempDir::new("rudder-module-secedit")?;
        let template_file = tmp_dir.path().join("template.ini");

        invoke_with_args(format!("/export /cfg {}", template_file.as_path().display()).as_str())?;

        let x = read_utf16_file(&template_file)?;

        let opt = ParseOption {
            enabled_escape: false,
            ..Default::default()
        };
        let mut template = Ini::load_from_str_opt(&x, opt).with_context(|| {
            format!(
                "Failed to read template file '{}'",
                template_file.as_path().display()
            )
        })?;

        for (k, v) in data {
            for (_, prop) in template.iter_mut() {
                for (t, _) in prop.clone().iter_mut() {
                    if k == t {
                        prop.remove(&k);
                        prop.insert(&k, v.to_string());
                    }
                }
            }
        }

        let config = tmp_dir.path().join("tmp.ini");
        template.write_to_file(&config)?;

        let data = read_to_string(&config)?;
        let data = data.replace(r"\\", r"\");
        let config = tmp_dir.path().join("config.ini");
        write_utf16_file(&config, &data)?;

        let db = tmp_dir.path().join("tmp.db");
        invoke_with_args(
            format!(
                "/import /db {} /cfg {}",
                db.as_path().display(),
                config.as_path().display()
            )
            .as_str(),
        )?;

        invoke_with_args(format!("/configure /db {}", db.as_path().display(),).as_str())?;

        tmp_dir.close()?;
    }

    println!("DONE");
    Ok(())
}

fn invoke_with_args(args: &str) -> Result<()> {
    const COMMAND: &str = "secedit.exe";
    let mut cmd = Command::new(COMMAND);
    cmd.stdin(Stdio::piped());
    cmd.stdout(Stdio::piped());
    cmd.stderr(Stdio::piped());

    if !args.is_empty() {
        cmd.args(args.split_whitespace());
    }

    let output = match cmd.spawn() {
        Ok(task) => task,
        Err(e) => bail!("Failed to execute command '{COMMAND} {args}' {e}"),
    }
    .wait_with_output()?;

    if !output.status.success() {
        let msg = String::from_utf8(output.stdout)?.to_string();
        bail!("{msg}");
    }

    Ok(())
}

fn read_utf16_file<P: AsRef<Path>>(path: P) -> Result<String> {
    let mut file = File::open(path)?;
    let mut buffer = Vec::new();

    file.read_to_end(&mut buffer)?;
    let data: WString<LittleEndian> = WString::from_utf16le(buffer)?;

    Ok(data.to_utf8())
}

fn write_utf16_file<P: AsRef<Path> + std::fmt::Debug>(path: P, buffer: &str) -> Result<()> {
    let mut file = File::create(path)?;
    for b in buffer.encode_utf16() {
        file.write_all(&b.to_le_bytes())?;
    }

    Ok(())
}
