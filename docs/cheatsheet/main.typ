#set page(
  paper: "a4",
  flipped: true,           // landscape
  margin: (x: 8mm, y: 8mm),// smaller margins
)

// From 2025/07 brand book
#let dark-cold-violet = color.rgb("#340499")
#let light-cold-violet = color.rgb("#ECECFF")
#let border-color = color.rgb("#d6deef")
#let title-font = "Raleway"
#let main-font = "Lato"
// Improvised
#let mono-font = "JetBrains Mono"

// ── Helpers ─────────────────────────────────────────────────

// Print mode: hide the "docs ↗" buttons. Enable with:
//   typst compile main.typ --input print=true
#let print = sys.inputs.at("print", default: "false") == "true"

// Coloured section header
#let section(title, color: dark-cold-violet, doc: none) = block(
  width: 100%,
  fill: color,
  radius: 3pt,
  inset: (x: 6pt, y: 4pt),
  below: 4pt,
)[
  #text(weight: "black", fill: white, size: 8pt, font: title-font)[#title]
  #if doc != none and not print {
    h(1fr)
    text(size: 6pt, fill: white)[#link(doc)[docs ↗]]
  }
]

// Two-column key → description table (flags, services, conditions, …)
#let kv(..rows) = grid(
  columns: (auto, 1fr),
  column-gutter: 6pt,
  row-gutter: 3pt,
  ..rows,
)

// Full-width sub-header row inside a kv table
#let kv-head(t) = grid.cell(colspan: 2, inset: (top: 2pt, bottom: 1pt))[
  #text(weight: "bold", size: 6.5pt, fill: dark-cold-violet)[#t]
]

// Lighter violet sub-header inside a section
#let subsection(title) = block(below: 3pt, above: 7pt)[
  #text(weight: "bold", size: 7.5pt, fill: dark-cold-violet, font: title-font)[#title]
]

// Small label printed above a code block
#let lbl(t) = text(size: 6.5pt)[#t]

// Highlighted logical / shell operator
#let op(s) = text(weight: "bold", fill: dark-cold-violet, font: mono-font)[#s]

// Resource link: domain text linking to its https URL
#let link-key(k) = text(weight: "bold", size: 7pt, font: mono-font)[#link("https://" + k)[#k]]

// ── Body (4 columns) ────────────────────────────────────────
#set text(size: 7pt, font: main-font)
#set par(leading: 4pt, spacing: 3pt)

#show raw.where(block: true): it => block(
  fill: light-cold-violet,
  inset: (x: 5pt, y: 3pt),
  radius: 2pt,
  width: 100%,
  above: 2pt,
  below: 7pt,
  text(size: 6.5pt, it),
)
#show raw.where(block: false): it => text(font: mono-font, size: 6.5pt, it)

#columns(4, gutter: 8pt)[

  #align(center, image("rudder-logo.svg", width: 80%))

  #section("Agent commands", doc: "https://docs.rudder.io/reference/current/reference/man.html")

  #lbl[Update policies and enforce them]
  ```
  rudder agent run
  ```

  #kv(
    kv-head("Linux flags"),
    [`-i`], [information mode],
    [`-v`], [(very) verbose mode],
    kv-head("Windows flags"),
    [`-Verbose`],        [information/verbose mode],
    [`-LogFile <path>`], [write output to a file],
  )

  #subsection("Other commands")
  #lbl[Create and send an inventory]
  ```
  rudder agent inventory
  ```
  #lbl[Check the agent is healthy]
  ```
  rudder agent health
  ```
  #lbl[Disable / enable the agent]
  ```
  rudder agent disable/enable
  ```
  #lbl[Clear locks and cache]
  ```
  rudder agent reset
  ```
  #lbl[List files modified by the agent]
  ```
  rudder agent modified
  ```
  #lbl[Diff a file vs. before the agent changed it]
  ```
  rudder agent diff <file>
  ```
  #lbl[Agent status summary]
  ```
  rudder agent info
  ```
  #lbl[Manual (Linux)]
  ```
  man rudder
  ```

  #section("Services")

  #subsection("On the server")
  #kv(
    [`rudder-jetty`], [web application],
    [`rudder-slapd`], [inventory database],
    [`postgresql`],   [main database],
  )

  #subsection("On the relay and server")
  #kv(
    [`rudder-relayd`], [relay daemon (reporting)],
  )

  #subsection("On all Linux systems")
  #kv(
    [`rudder-agent`],      [agent umbrella (the two below)],
    [`rudder-cf-execd`],   [schedules periodic agent runs],
    [`rudder-cf-serverd`], [policy server & remote trigger],
  )

  #colbreak()

  #section("Plugins management", doc: "https://docs.rudder.io/reference/current/plugins/index.html")

  #lbl[Refresh repository index and licenses]
  ```
  rudder package update
  ```
  #lbl[Install the license archive (offline server only)]
  ```
  rudder package install <name-license.tar.gz>
  ```
  #lbl[List installed / all available]
  ```
  rudder package list
  rudder package list --all
  ```
  #lbl[Show plugin details]
  ```
  rudder package show <plugin>
  ```
  #lbl[Install (online, or offline from .rpkg)]
  ```
  rudder package install <plugin> ...
  rudder package install <file.rpkg> ...
  ```
  #lbl[Upgrade an installed plugin]
  ```
  rudder package upgrade <plugin>
  ```
  #lbl[Enable / disable]
  ```
  rudder package enable/disable <plugin>
  ```
  #lbl[Remove a plugin]
  ```
  rudder package remove <plugin>
  ```

  #section("Paths")

  #subsection("Linux agent")
  #lbl[Policy server and agent configuration files]
  ```
  /opt/rudder/etc/policy_server.dat
  /opt/rudder/etc/agent.conf
  ```

  #subsection("Windows agent")
  #lbl[Install directory]
  ```
  C:\Program Files\Rudder\
  ```
  #lbl[Policy server and agent configuration files]
  ```
  C:\Program Files\Rudder\etc\
        policy-server.conf
  C:\Program Files\Rudder\etc\agent.conf
  ```

  #subsection("Server")
  #lbl[Configuration policies (git repository)]
  ```
  /var/rudder/configuration-repository
  ```
  #lbl[Files shared to nodes from the server]
  ```
  /var/rudder/configuration_directory/
          shared-files
  ```
  #lbl[Hooks (generation, campaigns)]
  ```
  /opt/rudder/etc/hooks.d
  ```
  #lbl[Rudder account (plugins access)]
  ```
  /opt/rudder/etc/rudder-pkg/rudder-pkg.conf
  ```
  #lbl[Webapp configuration]
  ```
  /opt/rudder/etc/rudder-web.properties
  /opt/rudder/etc/rudder-web.properties.d/
  ```

  #colbreak()

  #section("Logs")

  #lbl[Web application (server)]
  ```
  /var/log/rudder/webapp/webapp.log
  ```
  #lbl[Web application logging configuration (server)]
  ```
  /opt/rudder/etc/logback.xml
  ```
  #lbl[Compliance events: changes & errors (server)]
  ```
  /var/log/rudder/compliance/
        non-compliant-reports/
  ```
  #lbl[Relay & policy-server daemons (journald)]
  ```
  journalctl -u rudder-relayd
  journalctl -u rudder-cf-serverd
  ```
  #lbl[Last agent run output (Linux node)]
  ```
  /var/rudder/cfengine-community/outputs/
  ```
  #lbl[Windows agent]
  ```
  C:\Program Files\Rudder\logs
  ```

  #section("REST API", doc: "https://docs.rudder.io/api/")

  #lbl[Authenticate with an API token]
  ```
  curl -H "X-API-Token: <token>" \
    https://<server>/rudder/api/latest/...
  ```
  #lbl[Common endpoints]
  ```
  /nodes          /rules
  /directives     /groups
  /compliance/nodes/<id>
  /system/status
  ```

  #section("Advanced search syntax", doc: "https://docs.rudder.io/reference/current/usage/web_interface.html")

  You can use a query language to specify your search in
  the search bar:
  ```
  is:node in:ips 192.168.1.2
  ```
  #lbl[`is:node|group|parameter|directive|node` — the item
  you are searching for]
  #lbl[`in:property_name` — the property you are searching in]
  ```
  is:* in:name|id|description|
        long_description|enabled

  is:node in:hostname|os_type|os_name|
        os_version|os|os_kernel_version|
        os_service_pack|architecture|ram|
        ips|policy_server_id|properties|
        rudder_roles

  is:group in:dynamic

  is:directives in:dir_param_name|
        dir_param_value|technique_id|
        technique_name|technique_version

  is:rules in:directives|groups

  is:parameters in:parameter_name|
        parameter_value
  ```

  #section("Resources")

  #grid(
    columns: (auto, 1fr),
    column-gutter: 8pt,
    row-gutter: 7pt,
    inset: (y: 3pt),
    stroke: (_, y) => if y > 0 { (top: 0.4pt + border-color) },
    link-key("www.rudder.io"),    [Website],
    link-key("docs.rudder.io"),   [Documentation],
    link-key("docs.rudder.io/api"),   [API Documentation],
    link-key("issues.rudder.io"), [Bug tracker],
    link-key("chat.rudder.io"),   [Community chat],
  )

]

// End of first page
#pagebreak()

#columns(4, gutter: 8pt)[

  #section("Conditions")

  Conditions are
  case-sensitive and must match `[a-zA-Z0-9][a-zA-Z0-9_]*`

  #subsection("System conditions")

  #lbl[Various system information are defined by default]
  ```
  debian_13
  ubuntu_24_04
  rhel_10, rhel_10_1
  ```
  #lbl[Show all system conditions]
  ```
  rudder agent info -v
  ```

  #subsection("Result conditions")
  All methods define a
  ```
  <method>_<parameter>_<suffix>
  ```
  condition, where `<suffix>` is:
  #kv(
    [`_kept`],     [already compliant, no change],
    [`_repaired`], [changed to become compliant],
    [`_error`],    [execution failed],
    [`_ok`],       [special value, kept or repaired],
  )

  #subsection("Special conditions")
  #kv(
    [`any` / `true`], [always defined],
    [`false`],       [never defined],
  )

  #subsection("Logical operators")
  #kv(
    [#lbl[Group]], [#op("(")`condition_expression`#op(")")],
    [#lbl[Or]],    [`condition`#op("|")`other`],
    [#lbl[And]],   [`condition`#op(".")`other`],
    [#lbl[Not]],   [#op("!")`condition`],
  )

  #section("Technique YAML", doc: "https://docs.rudder.io/techniques/9.2/syntax.html")
  ```yaml
  id: my_technique
  name: My technique
  version: "1.0"
  params:
    - name: pkg
      type: string
  items:
    - name: Install
      method: package_present
      params:
        name: ${pkg}
      condition: debian
    - name: A block       # groups items
      items:
        - method: service_started
  ```

    
  #section("Techniques (rudderc)", doc: "https://docs.rudder.io/techniques/9.2/usage.html")

  #lbl[Scaffold a new technique project]
  ```
  rudderc new <name>
  cd <name>
  ```
  #lbl[Validate syntax (Linux + Windows)]
  ```
  rudderc check
  ```
  #lbl[Package as .zip for API import]
  ```
  rudderc build --export
  ```

  #colbreak()

  #section("Variables", doc: "https://docs.rudder.io/reference/current/usage/variables.html")

  Variables in:

  - Directives are evaluated at generation time
  - Techniques are evaluated at run time


  #subsection("In directives and techniques")
  #lbl[Node properties]
  ```
  ${node.properties[key]}
  ${node.properties[key][subkey]}
  ```
  #lbl[Node inventory data, computed at generation]
  ```
  ${node.inventory[hostname]}
  ${node.inventory[os][name]}
  ```
  #lbl[Available keys:]
  #kv(
    [`[nodeId]`],                        [node ID],
    [`[hostname]`],                      [hostname],
    [`[archDescription]`],               [arch, e.g. x86_64],
    [`[ram]`],                           [RAM in bytes],
    [`[timezone]`],                      [e.g. Europe/Paris],
    [`[policyServerId]`],                [policy server id],
    [`[os][name]`],                      [e.g. Debian],
    [`[os][fullName]`],                  [full OS name],
    [`[os][version]`],                   [e.g. 9.1],
    [`[os][kernelVersion]`],             [kernel version],
    [`[os][servicePack]`],               [OS service pack],
    [`[machine][machineType]`],          [physical, qemu, …],
    [`[machine][manufacturer]`],         [hardware vendor],
  )
  #subsection("In directives")

  #lbl[Default values (with node properties)]
  ```
  ${variable | default= "value"}
  ${variable |
    default= """ value with "quotes" """}
  ${variable | default= ${any_other_variable} }
  ${variable |
    default= ${var} | default="fallback"}
  ```

  #subsection("In techniques")
  #lbl[Technique parameters]
  ```
  ${technique_id.parameter_name}
  ${parameter_name}
  ```
  #lbl[Technique resources]
  ```
  ${resource_dir.my_resource}
  ```
  #lbl[Variables defined using methods]
  ```
  ${variable_prefix.string_name}
  ${variable_prefix.iterator_name}
  ${variable_prefix.dict_name[key]}
  ```

  #subsection("On the node, computed at runtime")

#lbl[System variables#if not print [ #text(size: 6pt, fill: dark-cold-violet)[#link("https://docs.cfengine.com/docs/lts/reference/special-variables/sys/")[(on Linux ↗)]]]]
  ```
  ${sys.host}
  ${sys.arch}
  ${sys.fqhost}
  ```
  #lbl[Constants]
  ```
  ${const.dollar}   // a literal $
  ${const.endl}     // newline (or ${const.n})
  ```

  #colbreak()

  #section("MiniJinja templating", doc: "https://docs.rudder.io/techniques/9.2/modules/template.html")

  #subsection("Variables")
  ```j2
  {{ vars.variable_prefix.my_variable }}
  {{ user.email }}
  {{ name | default('World') }}
  ```

  #subsection("Conditions")
  ```j2
  {% if classes.my_condition is defined %}
  condition is defined
  {% endif %}

  {% if not classes.my_condition is defined %}
  condition is not defined
  {% endif %}
  ```

  #subsection("Iterations")
  ```j2
  {% for item in vars.variable_prefix.dict %}
  {{ item }} is the current item value
  {{ item.key }} is the current item[key] value
  {% endfor %}

  {% for key,value in vars.prefix.dict | items %}
  {{ key }} has value {{ value }}
  {% endfor %}
  ```

  #subsection("Comments")
  ```j2
  {# ... #}
  ```

  #subsection("Base filters")
  ```j2
  {{ name | upper }}      {# lower, title #}
  {{ s | trim | replace('a','b') }}
  {{ list | length }}
  {{ list | join(', ') }}
  {{ list | sort | unique }}
  {{ list | first }}  {{ list | last }}
  {{ nums | min }}  {{ nums | max }}
  {{ nums | sum }}  {{ ratio | round(2) }}
  {{ value | int }}
  {{ obj | tojson }}
  ```

  #subsection("Filtering lists")
  ```j2
  {{ users | map(attribute='name') }}
  {{ items | selectattr('active') | list }}
  {{ items | rejectattr('hidden') | list }}
  ```

  #subsection("Additional filter")
  ```j2
  {{ s | b64encode }}   {{ s | b64decode }}
  {{ p | basename }}    {{ p | dirname }}
  {{ s | urlencode }}   {{ s | urldecode }}
  {{ s | hash('sha-256') }}   {# or sha-512 #}
  {{ s | quote }}       {# shell quoting #}
  {{ s | regex_escape }}
  {{ s | regex_replace('a(.*)', 'b$1') }}
  {{ s | regex_replace('x', 'y', 0) }}  {# 0=all #}
  {{ lookup('file', '/etc/issue') }}  {# raw file #}
  ```

  #colbreak()

  #subsection("Tests")
  ```j2
  {% if x is none %}{% endif %}
  {% if x is string %}  {# number, mapping #}
  {% if x is sequence %}{% endif %}
  {% if n is odd %}     {# even #}
  {% if item in list %}{% endif %}
  ```

  #subsection("Set & expressions")
  ```j2
  {% set role = "web" %}
  {{ "on" if enabled else "off" }}
  {{ a ~ "-" ~ b }}     {# concatenation #}
  ```

  #subsection("Loop helpers")
  ```j2
  {% for x in list %}
  {{ loop.index }}   {# index0 = 0-based #}
  {{ loop.first }} {{ loop.last }}
  {{ loop.length }}
  {% endfor %}
  ```

  #subsection("Whitespace control")
  ```j2
  {%- if x -%} ... {%- endif -%}
  {# - trims newlines around the tag #}
  ```

  #section("Mustache templating", doc: "https://docs.rudder.io/techniques/9.2/modules/template.html")

  #subsection("Variables")
  ```
  {{{vars.node.properties.variable_name}}}
  {{{vars.variable_prefix.string_name}}}
  {{{vars.variable_prefix.dict_name.key}}} 
  ```

  #subsection("Conditions")
  ```
  {{#classes.conditions}}
  condition is defined
  {{/classes.conditions}}

  {{^classes.conditions}}
  condition is defined
  {{/classes.conditions}}
  ```

  #subsection("Iterations")
  ```
  {{#vars.variable_prefix.iterator_name}}
  {{{.}}} is the current iterator_name value
  {{/vars.variable_prefix.iterator_name}}

  {{#vars.variable_prefix.dict_name}}
  {{{@}}} is the current dict_name key
  {{{.}}} is the current dict_name value
  {{{.name}}} is the current dict_name[name]
  {{/vars.variable_prefix.dict_name}}
  ```
]

#place(bottom + right, text(size: 5pt)[Built on #datetime.today().display("[year]-[month]-[day]")])
