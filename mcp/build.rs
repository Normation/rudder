//! Builds a compact index of the Rudder API `GET` endpoints from the OpenAPI source
//! (`webapp/sources/api-doc`), embedded for the `api_search` and `api_get` tools. Generated at
//! build time, so it always matches the API of the tree the server is built from.

use std::{
    env, fs,
    path::{Path, PathBuf},
};

use serde_json::{Value as Json, json};
use serde_yaml::Value;

/// Descriptions are cut to their first paragraph, then to this length
const MAX_DESCRIPTION: usize = 300;

fn main() {
    let spec = Path::new(env!("CARGO_MANIFEST_DIR")).join("../webapp/sources/api-doc");
    println!("cargo::rerun-if-changed={}", spec.display());

    let root = load(&spec.join("openapi.src.yml"));
    let paths = root["paths"]
        .as_mapping()
        .expect("OpenAPI source has a `paths` map");
    let mut endpoints = Vec::new();
    for (path, item) in paths {
        let path = path.as_str().expect("paths are strings");
        // Either the whole path item is in a file, or each method points to its own file
        let (get, file) = match item["$ref"].as_str() {
            Some(reference) => {
                let file = spec.join(reference);
                match load(&file).get("get") {
                    Some(get) => (get.clone(), file),
                    None => continue,
                }
            }
            None => match item["get"]["$ref"].as_str() {
                Some(reference) => {
                    let file = spec.join(reference);
                    (load(&file), file)
                }
                None => continue,
            },
        };
        let parameters: Vec<Json> = get["parameters"]
            .as_sequence()
            .into_iter()
            .flatten()
            .map(|parameter| describe_parameter(&resolve(parameter, &file)))
            .collect();
        endpoints.push(json!({
            "path": path,
            "summary": text(&get["summary"]),
            "description": short(&text(&get["description"])),
            "operation_id": text(&get["operationId"]),
            "tags": get["tags"].as_sequence().into_iter().flatten().filter_map(Value::as_str).collect::<Vec<_>>(),
            "parameters": parameters,
        }));
    }

    let out =
        PathBuf::from(env::var("OUT_DIR").expect("cargo sets OUT_DIR")).join("api_index.json");
    fs::write(
        &out,
        serde_json::to_string(&endpoints).expect("index serializes"),
    )
    .unwrap_or_else(|e| panic!("could not write {}: {e}", out.display()));
}

fn load(file: &Path) -> Value {
    let content = fs::read_to_string(file)
        .unwrap_or_else(|e| panic!("could not read {}: {e}", file.display()));
    serde_yaml::from_str(&content)
        .unwrap_or_else(|e| panic!("could not parse {}: {e}", file.display()))
}

/// A parameter, or the component a `$ref` points to (relative to the file using it)
fn resolve(parameter: &Value, file: &Path) -> Value {
    match parameter["$ref"].as_str() {
        Some(reference) => load(
            &file
                .parent()
                .expect("files have a directory")
                .join(reference),
        ),
        None => parameter.clone(),
    }
}

fn describe_parameter(parameter: &Value) -> Json {
    let schema = &parameter["schema"];
    json!({
        "name": text(&parameter["name"]),
        "location": text(&parameter["in"]),
        "required": parameter["required"].as_bool().unwrap_or(false),
        "description": short(&text(&parameter["description"])),
        "kind": text(&schema["type"]),
        "values": schema["enum"].as_sequence().into_iter().flatten().filter_map(Value::as_str).collect::<Vec<_>>(),
    })
}

fn text(value: &Value) -> String {
    value.as_str().unwrap_or_default().trim().to_owned()
}

fn short(description: &str) -> String {
    let first = description
        .split("\n\n")
        .next()
        .unwrap_or_default()
        .replace('\n', " ");
    if first.chars().count() > MAX_DESCRIPTION {
        let cut: String = first.chars().take(MAX_DESCRIPTION).collect();
        format!("{cut}…")
    } else {
        first
    }
}
