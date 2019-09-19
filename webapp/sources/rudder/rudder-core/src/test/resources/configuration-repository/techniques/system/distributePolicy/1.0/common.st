# Common variable for distribute policy system technique
bundle common distribute_policy_common {
  vars:
    "directiveId"            string => "&TRACKINGKEY&";
    "report_db_name"         string => "&RUDDER_REPORTS_DB_NAME&";
    "report_db_user"         string => "&RUDDER_REPORTS_DB_USER&";
    "relay_sync_method"      string => "&RELAY_SYNC_METHOD&";
    "relay_sync_promises"    string => "&RELAY_SYNC_PROMISES&";
    "relay_sync_sharedfiles" string => "&RELAY_SYNC_SHAREDFILES&";

  classes:

}
