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

[processing.reporting]
directory = "{{{vars.g.rudder_var}}}/reports"
{{#classes.root_server}}
output = "database"
{{/classes.root_server}}
{{^classes.root_server}}
output = "upstream"
{{/classes.root_server}}
skip_logs = false

[processing.reporting.catchup]
frequency = 10
limit = 50

[output.database]
{{#classes.root_server}}
url = "postgres://{{{vars.rudder_postgresql.db_user}}}:{{{vars.rudder_postgresql.db_pass}}}@{{{vars.rudder_postgresql.host}}}/{{{vars.rudder_postgresql.db_name}}}"
{{/classes.root_server}}
{{^classes.root_server}}
url = "postgres://user:password@host/rudder"
{{/classes.root_server}}
max_pool_size = 10

[output.upstream]
url = "https://{{{vars.server_info.policy_server}}}"
user = "{{{vars.g.davuser}}}"
password = "{{{vars.g.davpw}}}"
verify_certificates = false

[remote_run]
command = "/opt/rudder/bin/rudder"
use_sudo = true

