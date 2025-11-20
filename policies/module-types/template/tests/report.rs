// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2025 Normation SAS

use rudder_module_template::diff;

#[test]
fn test_diff() {
    let file_a = "
fn main() {
    println!(\"Hello, World!\");
}
";

    let file_b = "
fn main() {
    println!(\"Hello, Ferris!\");
}
";

    let wanted_diff = "@@ -2,3 +2,3 @@
 fn main() {
-    println!(\"Hello, World!\");
+    println!(\"Hello, Ferris!\");
 }
";

    let x = diff(file_a.to_string(), file_b.to_string());
    assert_eq!(x, wanted_diff)
}
