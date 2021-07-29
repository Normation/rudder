# Common variable for distribute policy system technique
bundle common distribute_policy_common {
  vars:
    "directiveId"            string => "&TRACKINGKEY&";
    "report_db_url"          string => "&RUDDER_REPORTS_DB_URL&";
    "report_db_password"     string => "&RUDDER_REPORTS_DB_PASSWORD&";
    "relay_sync_method"      string => "&RELAY_SYNC_METHOD&";
    "relay_sync_promises"    string => "&RELAY_SYNC_PROMISES&";
    "relay_sync_sharedfiles" string => "&RELAY_SYNC_SHAREDFILES&";

  classes:

}
