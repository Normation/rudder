bundle agent Create_file_rudder_reporting
{
  vars:
    "promisers"          slist => { @{this.callers_promisers}, cf_null }, policy => "ifdefined";
    "class_prefix"      string => canonify(join("_", "promisers"));
    "args"               slist => { };

  methods:

    !(directory_create__tmp_foo_ok)::
      "dummy_report" usebundle => _classes_noop("file_create__tmp_foo_bar");
      "dummy_report" usebundle => log_rudder("File create /tmp/foo/bar if directory_create__tmp_foo_ok", "file_create__tmp_foo_bar", "${class_prefix}", @{args});
}
