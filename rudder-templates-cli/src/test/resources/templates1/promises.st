body common control
{
    android::
        # if native syslog is not available, use cfengine implementation (eg: android)
        syslog_host => "${server_info.cfserved}";
        syslog_port => "&SYSLOGPORT&";
}


bundle common rudder_roles
{

  classes:
      # on changes only, we only reports on repaired or error message
      "changes_only"     expression => strcmp("&RUDDER_REPORT_MODE&", "changes-only");
      "reports_disabled" expression => strcmp("&RUDDER_REPORT_MODE&", "reports-disabled");

      # full compliance is the default mode
      "full_compliance" not => "changes_only|reports_disabled";
}
