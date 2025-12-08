// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2025 Normation SAS

use rudder_module_type::diff::diff;

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

    let wanted_diff = "@@ -1,4 +1,4 @@
 
 fn main() {
-    println!(\"Hello, World!\");
+    println!(\"Hello, Ferris!\");
 }
";

    let x = diff(file_a, file_b);
    assert_eq!(x, wanted_diff)
}
