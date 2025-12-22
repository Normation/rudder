use anyhow::{Context, Result, anyhow, bail};
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

impl Secedit {
    pub fn new() -> Result<Self> {
        Ok(Self {
            tmp_dir: TempDir::new("rudder-module-secedit")?,
        })
    }

    pub fn run(&self, data: Map<String, Value>, audit: bool) -> Result<()> {
        let mut template = self.export()?;
        if audit {
            audit_template(&template, &data)?;
            println!("Audit DONE");
        } else {
            template_search_and_replace(&mut template, &data)?;
            self.apply_template(&template)?;
            println!("DONE");
        }

        Ok(())
    }

    fn apply_template(&self, template: &Ini) -> Result<()> {
        let config = self.tmp_dir.path().join("tmp.ini");
        template.write_to_file(&config)?;

        let data = read_to_string(&config)?;
        let data = data.replace(r"\\", r"\"); // FIXME: Escaping issue
        let config = self.tmp_dir.path().join("config.ini");
        write_utf16_file(&config, &data)?;

        let db = self
            .tmp_dir
            .path()
            .join("tmp.db")
            .as_path()
            .display()
            .to_string();

        let cmd = format!("/import /db {} /cfg {}", db, config.as_path().display());
        invoke_with_args(&cmd)?;

        let cmd = format!("/configure /db {}", db);
        invoke_with_args(&cmd)?;

        Ok(())
    }

    fn export(&self) -> Result<Ini> {
        let template_file = self.tmp_dir.path().join("template.ini");

        let cmd = format!("/export /cfg {}", template_file.as_path().display());
        invoke_with_args(&cmd)?;

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
        let msg = String::from_utf8_lossy(&output.stdout);
        bail!("{msg}");
    }

    Ok(())
}

fn for_each_section<F>(template: &mut Ini, data: &Map<String, Value>, mut f: F) -> Result<()>
where
    F: FnMut(&mut ini::Properties, &str, &str) -> Result<()>,
{
    for (section, section_data) in data {
        let props = template
            .section_mut(Some(section))
            .ok_or_else(|| anyhow!("section '{section}' does not exist"))?;

        let entries = section_data
            .as_object()
            .ok_or_else(|| anyhow!("Invalid data '{section_data:?}', expected JSON object"))?;

        for (key, value) in entries {
            f(props, key, &value.to_string())?;
        }
    }

    Ok(())
}

fn audit_template(template: &Ini, data: &Map<String, Value>) -> Result<()> {
    let mut template = template.clone();

    for_each_section(&mut template, data, |props, key, expected| {
        let actual = props
            .get(key)
            .ok_or_else(|| anyhow!("'{key}' does not exist"))?;

        if actual != expected {
            println!("{key} set to '{actual}' expected '{expected}'");
        }

        Ok(())
    })
}

fn template_search_and_replace(template: &mut Ini, data: &Map<String, Value>) -> Result<()> {
    for_each_section(template, data, |props, key, value| {
        if props.contains_key(key) {
            props.insert(key, value);
            Ok(())
        } else {
            bail!("'{key}' does not exist");
        }
    })
}

fn read_utf16_file<P: AsRef<Path>>(path: P) -> Result<String> {
    let mut file = File::open(path)?;
    let mut buffer = Vec::new();

    file.read_to_end(&mut buffer)?;
    let data: WString<LittleEndian> = WString::from_utf16le(buffer)?;

    Ok(data.to_utf8())
}

fn write_utf16_file<P: AsRef<Path>>(path: P, buffer: &str) -> Result<()> {
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
            value = Pi
            [Settings]
            abc = 21",
        )
        .unwrap();

        let data = serde_json::from_str(
            r#"
        {
            "User": {
                "name": "Ferris",
                "value": 42
            },
            "Settings": {
                "abc": 12
            }
        }"#,
        )
        .unwrap();

        let res = template_search_and_replace(&mut template, &data);

        assert!(res.is_ok());
        assert_eq!(template.get_from(Some("User"), "name"), Some("\"Ferris\""));
        assert_eq!(template.get_from(Some("User"), "value"), Some("42"));
        assert_eq!(template.get_from(Some("Settings"), "abc"), Some("12"));
    }

    #[test]
    fn test_template_audit() {
        let template = Ini::load_from_str(
            "[User]
            name = Ferris
            value = Pi
            [Settings]
            abc = 21",
        )
        .unwrap();

        let data = serde_json::from_str(
            r#"
        {
            "User": {
                "name": "Ferris",
                "value": "Pi"
            },
            "Settings": {
                "abc": 21
            }
        }"#,
        )
        .unwrap();

        let res = audit_template(&template, &data);

        assert_eq!(res.unwrap(), ());
    }
}
