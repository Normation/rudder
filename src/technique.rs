use serde::{Deserialize, Serialize};
use serde_json::Value;
use crate::error::*;
use std::fs;
use std::path::Path;

#[derive(Serialize, Deserialize)]
struct Technique {
    name: String,
    description: String,
    version: String,
    bundle_name: String,
    parameter: Vec<Value>,
    bundle_args: Vec<String>,
    method_calls: Vec<MethodCall>,
}

#[derive(Serialize, Deserialize)]
struct MethodCall {
    method_name: String,
    class_context: String,
    args: Vec<String>,
    component: String,
}

pub fn translate_file(json_file: &Path, rl_file: &Path) -> Result<()> {
    // we use if let for error conversion
    // we don't use match for better linear reading
    let json_data = fs::read_to_string(&json_file);
    if let Err(_) = json_data { return Err(Error::User(format!("Cannot read file {}", json_file.to_string_lossy()))) }
    let technique = serde_json::from_str::<Technique>(&json_data.unwrap());
    if let Err(_) = technique { return Err(Error::User(format!("Invalid technique in file {}", json_file.to_string_lossy()))) } 
    let rl_technique = translate(&technique.unwrap())?;
    if let Err(_) = fs::write(&rl_file, rl_technique) { return Err(Error::User(format!("Cannot write file {}", rl_file.to_string_lossy()))) }
    Ok(())
}

fn translate_call(call: &MethodCall) -> Result<String> {
    let args = call.args.iter().map(|x| format!("\"{}\"",x)).collect::<Vec<String>>().join(",");
    let out = format!(r#"  if {context} => {name}({args})"#,
        context = call.class_context,
        name = call.method_name,
        args = args);
    Ok(out)
}

fn translate(technique: &Technique) -> Result<String> {
    let parameters_meta = serde_json::to_string(&technique.parameter);
    if let Err(_) = parameters_meta { return Err(Error::User("Unable to parse technique file".to_string())) }
    let parameters = technique.bundle_args.join(",");
    let mut call_list = Vec::new();
    for c in &technique.method_calls {
        call_list.push(translate_call(c)?);
    }
    let calls = call_list.join("\n");
    let out = format!(r#"@format=0
# This file has been generated with rltranslate 

@description="{description}"
@version="{version}"
@parameters={parameters_meta}

resource {name}({parameters})

{name} state technique() {{
{calls}
}}
"#, description=technique.description,
    version=technique.version,
    name=technique.name,
    parameters_meta=parameters_meta.unwrap(),
    parameters=parameters,
    calls=calls);
    Ok(out)
}

//#[cfg(test)]
//mod tests {
//    use super::*;
//
//    #[test]
//    fn test_json() {
//        let data = r#"
//{
//  "name": "variable",
//  "description": "",
//  "version": "1.0",
//  "bundle_name": "variable",
//  "parameter": [
//    {
//      "constraints": {
//        "allow_whitespace_string": false,
//        "allow_empty_string": false,
//        "max_length": 16384
//      },
//      "name": "iname",
//      "id": "53042794-4d2a-41c7-a690-b0d760a78a51"
//    },
//    {
//      "constraints": {
//        "allow_whitespace_string": false,
//        "allow_empty_string": false,
//        "max_length": 16384
//      },
//      "name": "ip",
//      "id": "aa74f824-6085-46b4-94b4-42803760fd61"
//    }
//  ],
//  "bundle_args": [
//    "iname",
//    "ip"
//  ],
//  "method_calls": [
//    {
//      "method_name": "variable_string",
//      "class_context": "any",
//      "args": [
//        "foo",
//        "bar",
//        "vim"
//      ],
//      "component": "Variable string"
//    },
//    {
//      "method_name": "package_state",
//      "class_context": "any",
//      "args": [
//        "${foo.bar}",
//        "",
//        "",
//        "",
//        "present"
//      ],
//      "component": "Package state"
//    }
//  ]
//}
//"#;
//        let p: Result<Technique> = serde_json::from_str(data);
//        assert!(p.is_ok());
//        //assert_eq!(p.unwrap().name, "variable".to_string()); 
//        let s = translate(&p.unwrap());
//        assert!(s.is_ok());
//        print!("{}",s.unwrap());
//    }
//}


