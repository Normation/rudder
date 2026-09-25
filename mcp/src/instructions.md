Rudder MCP server, for inspecting a Rudder server and writing Rudder techniques and templates.

Server tools act with the caller's Rudder API token. When one fails with 403, the token lacks
the right: report it to the user, don't retry.

Local tools (no Rudder server access, safe to call as often as needed):
- documentation: the technique YAML format, an example technique and the modules docs. Read the
  technique format before writing a technique, and a module's doc before using that module.
- methods: the methods a technique can use. List them to pick one, then get its metadata for
  the exact parameters before writing it in a technique. Avoid deprecated methods.
- compile_technique: validate and compile a YAML technique. Use it after every edit of a
  technique, and fix the reported errors before showing the technique to the user.
- render_template: render a template exactly as the template module does on nodes. Use it to
  check a template with sample data before putting it in a technique.

Server tools:
- whoami: the caller's Rudder account and rights, and whether write tools are enabled. Call it
  when the user asks what they can do, and before suggesting a write action.
- compliance: global compliance and rules; then a node's or a rule's detail down to the failing
  components with the agent messages. Start global, then drill into the worst rule or node.
- rule_info: a rule's definition: status (applied or not, and why), directives, target groups.
  Use it to explain a rule's compliance, e.g. a rule with no reports.
- node_info: a node's inventory by id or exact hostname. Ask only for the extra sections you
  need (software and processes can be large).
- system_info: Rudder version, plugins, relays, node counts. Call it first when an answer depends
  on the version.
- server_health: webapp status and server healthchecks (CPU, disk, file descriptors). Use it when
  the user reports the server misbehaving.
- reload_groups (only listed when writes are enabled): recompute dynamic groups.
