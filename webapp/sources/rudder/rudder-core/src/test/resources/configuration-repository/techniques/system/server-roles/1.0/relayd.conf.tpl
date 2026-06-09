# Format is TOML 0.5 (https://github.com/toml-lang/toml/blob/v0.5.0/README.md)

[general]

nodes_list_file = "{{{vars.g.rudder_var}}}/lib/relay/nodeslist.json"
nodes_certs_file = "{{{vars.g.rudder_var}}}/lib/ssl/allnodescerts.pem"
node_id = "{{{vars.g.uuid}}}"
listen = "127.0.0.1:3030"

# Use the number of CPUs
#core_threads = "4"
blocking_threads = 100

[processing.inventory]
directory = "{{{vars.g.rudder_var}}}/inventories"
{{#classes.root_server}}
output = "disabled"
{{/classes.root_server}}
{{^classes.root_server}}
output = "upstream"
{{/classes.root_server}}

[processing.inventory.catchup]
frequency = 10
limit = 50

[processing.inventory.cleanup]
frequency = "10min"
retention = "1day"

[processing.reporting]
directory = "{{{vars.g.rudder_var}}}/reports"
{{#classes.root_server}}
output = "database"
{{/classes.root_server}}
{{^classes.root_server}}
output = "upstream"
{{/classes.root_server}}
skip_event_types = []

[processing.reporting.catchup]
frequency = 10
limit = 0

[processing.reporting.cleanup]
frequency = "10min"
retention = "1day"

[output.database]
{{#classes.root_server}}
url = "postgres://{{{vars.rudder_postgresql.db_user}}}@{{{vars.rudder_postgresql.host}}}/{{{vars.rudder_postgresql.db_name}}}"
password = "{{{vars.rudder_postgresql.db_pass}}}"
{{/classes.root_server}}
{{^classes.root_server}}
url = "postgres://user@host/rudder"
password = "password"
{{/classes.root_server}}
max_pool_size = 10

[output.upstream]
url = "https://{{{vars.server_info.policy_server}}}"
user = "{{{vars.g.davuser}}}"
password = "{{{vars.g.davpw}}}"
{{#classes.rudder_verify_certs}}
verify_certificates = true
{{/classes.rudder_verify_certs}}
{{^classes.rudder_verify_certs}}
verify_certificates = false
{{/classes.rudder_verify_certs}}

[remote_run]
command = "/opt/rudder/bin/rudder"
use_sudo = true

[shared_files]
path = "{{{vars.g.rudder_var}}}/shared-files/"

[shared_folder]
path = "{{{vars.g.shared_files}}}/"
