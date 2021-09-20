# Format is TOML 0.5 (https://github.com/toml-lang/toml/blob/v0.5.0/README.md)

[general]

nodes_list_file = "{{{vars.g.rudder_var}}}/lib/relay/nodeslist.json"
{{#classes.root_server}}
nodes_certs_file = "{{{vars.g.rudder_var}}}/lib/ssl/allnodescerts.pem"
{{/classes.root_server}}
{{^classes.root_server}}
nodes_certs_file = "{{{vars.g.rudder_var}}}/lib/ssl/nodescerts.pem"
{{/classes.root_server}}

node_id = "{{{vars.g.uuid}}}"
listen = "127.0.0.1:3030"

# Use the number of CPUs
#core_threads = "4"
#blocking_threads = 100

# Use cert pinning
peer_authentication = "cert_pinning"

# Use proper port
https_port = {{{vars.system_rudder_relay_configuration.https_port}}}

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

{{#classes.root_server}}
[output.database]
url = "{{{vars.system_rudder_relay_configuration.db_url}}}"
password = "{{{vars.system_rudder_relay_configuration.db_password}}}"
max_pool_size = 10
{{/classes.root_server}}

[output.upstream]
host = "{{{vars.server_info.policy_server}}}"
user = "{{{vars.g.davuser}}}"
password = "{{{vars.g.davpw}}}"

[remote_run]
command = "{{{vars.g.rudder_base}}}/bin/rudder"
use_sudo = true

[shared_files]
path = "{{{vars.g.rudder_var}}}/shared-files/"

[shared_folder]
path = "{{{vars.g.shared_files}}}/"
