use anyhow::{Context, Result, bail};
use ini::{Ini, ParseOption};
use serde_json::{Map, Value};
use std::fs::{File, read_to_string};
use std::io::{Read, Write};
use std::path::Path;
use std::process::{Command, Stdio};
use tempdir::TempDir;
use utf16string::{LittleEndian, WString};

pub struct Secedit {
    tmp_dir: TempDir,
}

impl Default for Secedit {
    fn default() -> Self {
        Self {
            tmp_dir: TempDir::new("rudder-module-secedit")
                .expect("Could not create temporary directory"),
        }
    }
}

impl Secedit {
    pub fn run(&self, data: Map<String, Value>, audit: bool) -> Result<()> {
        if audit {
            unimplemented!("Audit mode not yet implemented!");
        } else {
            let mut template = self.export()?;
            Self::template_search_and_replace(&mut template, data)?;
            self.import(template).and_then(Self::configure)?;
            println!("DONE");
        }

        Ok(())
    }

    fn template_search_and_replace(template: &mut Ini, data: Map<String, Value>) -> Result<()> {
        for (section, section_data) in data {
            for (sec, prop) in template.iter_mut() {
                if let Some(sec) = sec
                    && sec == section
                {
                    let data = match section_data {
                        Value::Object(ref o) => o,
                        _ => bail!("Invalid data '{section_data:?}' expected JSON object"),
                    };

                    for (k, v) in data {
                        for (t, _) in prop.clone().iter_mut() {
                            if k == t {
                                prop.remove(k);
                                prop.insert(k, v.to_string());
                            }
                        }
                    }
                }
            }
        }

        Ok(())
    }

    fn configure(db: String) -> Result<()> {
        Self::invoke_with_args(format!("/configure /db {}", db).as_str())
    }

    fn import(&self, template: Ini) -> Result<String> {
        let config = self.tmp_dir.path().join("tmp.ini");
        template.write_to_file(&config)?;

        let data = read_to_string(&config)?;
        let data = data.replace(r"\\", r"\");
        let config = self.tmp_dir.path().join("config.ini");
        write_utf16_file(&config, &data)?;

        let db = self
            .tmp_dir
            .path()
            .join("tmp.db")
            .as_path()
            .display()
            .to_string();

        Self::invoke_with_args(
            format!("/import /db {} /cfg {}", db, config.as_path().display()).as_str(),
        )?;

        Ok(db.to_string())
    }

    fn export(&self) -> Result<Ini> {
        let template_file = self.tmp_dir.path().join("template.ini");

        Self::invoke_with_args(
            format!("/export /cfg {}", template_file.as_path().display()).as_str(),
        )?;

        let data = read_utf16_file(&template_file)?;
        let opt = ParseOption {
            enabled_escape: false,
            ..Default::default()
        };
        let template = Ini::load_from_str_opt(&data, opt).with_context(|| {
            format!(
                "Failed to read template file '{}'",
                template_file.as_path().display()
            )
        })?;

        Ok(template)
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

#[cfg(test)]
mod test {
    use super::*;

    #[test]
    fn test_template_search_and_replace() {
        let mut template = Ini::load_from_str(
            "[User]
            name = Ferris
            value = Pi",
        )
        .unwrap();

        let data = r#"
        {
            "User": {
                "name": "Ferris",
                "value": "42"
            }
        }"#;
        let data = serde_json::from_str(data).unwrap();

        let res = Secedit::template_search_and_replace(&mut template, data);

        assert!(res.is_ok());
        assert_eq!(template.get_from(Some("User"), "value"), Some("\"42\""));
    }
}
