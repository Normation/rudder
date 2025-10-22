# Template module

The module supports the following template engines:

* MiniJinja (which is a subset of Jinja2)
* Mustache
* Jinja2 (available only on Linux)

Minijinja is the default and preferred engine, Jinja2 and Mustache are provided for backward compatibility.
The Minijinja and Mustache engines are native and fast, Jinja2 is very slow, requires a Python interpreter, but allows dynamic extensions.

The module takes the following arguments:

| Name | Description | Possible values |
| ---- | ----------- | --------------- |
| `path` | Output file path | A path on the filesystem |
| `template_path` | Source template path (mutually exclusive with template_src) | A path on the filesystem that points to a template to be rendered |
| `template_src` | Inlined source template (mutually exclusive with template_path) | A template in the form of a string (for example: `{{%-top-}}`) |
| `engine` | Template engine | `mustache`, `minijinja`, `jinja2` (default: `minijinja`) |
| `data` | JSON data used for templating | A valid JSON |
| `show_content` | Controls output of diffs in the report | `true`/`false` (default: `true`) |

## CLI

The module provides a CLI to help debug the rendering and audit of templates.

```shell
$ /opt/rudder/bin/rudder-module-template --help
Usage: rudder-module-template [OPTIONS] --template <TEMPLATE> --data <DATA> --out <OUT>

Options:
  -e, --engine <ENGINE>      Template engine [default: minijinja] [possible values: mustache, minijinja, jinja2]
  -t, --template <TEMPLATE>  Template file
  -d, --data <DATA>          JSON data file
  -o, --out <OUT>            Output file
  -a, --audit                Audit mode
  -h, --help                 Print help
  -V, --version              Print version
```

## Minijinja 

MiniJinja is a subset of the Jinja2 templating language, implemented in pure Rust.
For a complete reference on MiniJinja syntax, see the [Jinja2 documentation](http://jinja.pocoo.org/docs/dev/templates/)
and the [MiniJinja compatibility](https://github.com/mitsuhiko/minijinja/blob/main/COMPATIBILITY.md).

### Syntax

This section presents some simple cases that cover what can be done with MiniJinja templating.

Note: You can add comments in the template, that will not be rendered in the output file with:

```
{# ... #}.
```

#### Conditions

To display content based on conditions definition:

```
{% if classes.my_condition is defined  %}
   display this if defined
{% endif %}
{% if not classes.my_condition is defined %}
   display this if not defined
{% endif %}
```

Note: You cannot use condition expressions here.

You can also use other tests, for example other built-in ones:

```
{% if vars.variable_prefix.my_number is odd  %}
   display if my_number is odd
{% endif %}
```

#### Scalar variables

Here is how to display a scalar variable value (integer, string, ...), if you have defined `variable_string("variable_prefix", "my_variable", "my_value")`:

```
{{ vars.variable_prefix.my_variable }}
```

You can also modify what is displayed by using filters.

```
{{ vars.variable_prefix.my_variable | uppercase }}
```

Will display the variable in uppercase.

#### Iteration

To iterate over a list, for example defined with:

```
variable_iterator("variable_prefix", "iterator_name", "a,b,c", ",")
```

Use the following file:

```
{% for item in vars.variable_prefix.iterator_name %}
{{ item }} is the current iterator_name value
{% endfor %}
```

Which will be expanded as:

```
a is the current iterator_name value
b is the current iterator_name value
c is the current iterator_name value
```

To iterate over a container defined by the following json file, loaded with `variable_dict_from_file("variable_prefix", "dict_name", "path")`:

```
{
   "hosts": [
       "host1",
       "host2"
   ],
   "files": [
       {"name": "file1", "path": "/path1", "users": [ "user1", "user11" ] },
       {"name": "file2", "path": "/path2", "users": [ "user2" ] }
   ],
   "properties": {
       "prop1": "value1",
       "prop2": "value2"
   }
}
```

Use the following template:

```
{% for item in vars.variable_prefix.dict_name.hosts %}
{{ item }} is the current hosts value
{% endfor %}

# will display the name and path of the current file
{% for file in vars.variable_prefix.dict_name.files %}
{{ file.name }}: {{ file.path }}
{% endfor %}

# will display the users list of each file
{% for file in vars.variable_prefix.dict_name.files %}
{{ file.name }}: {{ file.users|join(' ') }}
{% endfor %}


# will display the current properties key/value pair
{% for key, value in vars.variable_prefix.dict_name.properties.items() %}
{{ key }} -> {{ value }}
{% endfor %}
```

Which will be expanded as:

```
host1 is the current hosts value
host2 is the current hosts value

# will display the name and path of the current file
file1: /path1
file2: /path2

# will display the users list of each file
file1: user1 user11
file2: user2

# will display the current properties key/value pair
prop1 -> value1
prop2 -> value2
```

#### Filters

| Name | Description | Required Args | Optional Args | Example |
| ---- | ----------- | ------------- | ------------- | ------- |
| `abs`  |  Returns the absolute value of a number. | None | None | `\|a - b\| = {{ (a - b)\|abs }} -> \|2 - 4\| = 2` |
| `attr` | Looks up an attribute. | `key` | None | `{{ value['key'] == value\|attr('key') }} -> true` |
| `batch` | Batch items. | `count` | `fill_with` | `{% for row in items\|batch(3, '&nbsp;') %}` |
| `bool` | Converts the value into a boolean value. | None | None | `{{ 42\|bool }} -> true` |
| `capitalize` | Convert the string with all its characters lowercased apart from the first char which is uppercased. | None | None | `{{ chapter.title\|capitalize }}` |
| `default` | If the value is undefined it will return the passed default value, otherwise the value of the variable: | None | `other` | `{{ my_variable\|default("my_variable was not defined") }}` |
| `dictsort` | Dict sorting functionality. | None | `by`, `case_sensitive`, `reverse` | `{{ dict\|sort('reverse') }}` |
| `escape` | Escapes a string. By default to HTML. | None | None | `{{ value\|escape }}` |
| `first` | Returns the first item from an iterable. | None | None |  `{{ user.email_addresses\|first\|default('no user') }}` |
| `float` | Converts a value into a float. | None | None | `{{ "42.5"\|float == 42.5 }} -> true` |
| `groupby` | Group a sequence of objects by an attribute. | None | `attribute`, `default`, `case_sensitive` | `{% for city, items in users\|groupby("city") %}` |
| `indent` | Indents Value with spaces | `width` | `indent_first_line`, `indent_blank_lines` | `{{ global_config\|indent(2) }}`
| `int` | Converts a value into an integer. | None | None | `{{ "42"\|int == 42 }} -> true` |
| `items` | Returns an iterable of pairs (items) from a mapping. | None | None | `{% for key, value in my_dict\|items %}` |
| `join` | Joins a sequence by a character | None | `joiner` | `{{ "Foo Bar Baz" \| join(", ") }} -> foo, bar, baz` |
| `last` | Returns the last item from an iterable. | None | None | `{% with update = updates\|last %}` |
| `length` | Returns the “length” of the value | None | None | ` {{ results\|length }}` |
| `lines` | Splits a string into lines. | None | None | `{{ "foo\nbar\nbaz"\|lines }} -> ["foo", "bar", "baz"]` |
| `list` | Converts the input value into a list. | None | None | `{{ value\|list }}` |
| `lower` | Converts a value to lowercase. | None | None | `{{ chapter.title\|lower }}` |
| `map` | Applies a filter to a sequence of objects or looks up an attribute. | `attribute` | None | `{{ users\|map(attribute='username')\|join(', ') }}` |
| `max` | Returns the largest item from an iterable. | None | None | `{{ [1, 2, 3, 4]\|max }} -> 4` |
| `min` | Returns the smallest item from an iterable. | None | None | `{{ [1, 2, 3, 4]\|min }} -> 1` |
| `pprint` | Pretty print a variable. | None | None | `{{ value\|pprint }}` |
| `reject` | Creates a new sequence of values that don’t pass a test. | `test_name` | None | `[1, 2, 3, 4]\|reject("odd")` |
| `rejectattr` | Creates a new sequence of values of which an attribute does not pass a test. | `attr` | `test_name` | `{{ users\|rejectattr("id", "even") }}` |
| `replace` | Does a string replace. | `from`, `to` | None | `{{ "Hello World"\|replace("Hello", "Goodbye") }} -> Goodbye World` |
| `reverse` | Reverses an iterable or string | None | None | `{% for user in users\|reverse %}` |
| `round` | Round the number to a given precision. | None | `precision` | `{{ 42.55\|round }} -> 43.0` |
| `safe` | Marks a value as safe. This converts it into a string. | None | None | `{{ value\|safe }}` |
| `select` | Creates a new sequence of values that pass a test. | None | `test_name` | `{{ [1, 2, 3, 4]\|select("odd") }} -> [1, 3]` |
| `selectattr` | Creates a new sequence of values of which an attribute passes a test. | `attr` | `test_name` | `{{ users\|selectattr("id", "even") }} -> returns all users with an even id` |
| `slice` | Slice an iterable and return a list of lists containing those items. | `count` | `fill_with` | `{% for column in items\|slice(3) %}` |
| `sort` | Returns the sorted version of the given list. | None | `test_name` | `{{ [1, 3, 2, 4]\|sort }} -> [4, 3, 2, 1]` |
| `split` | Split a string into its substrings, using split as the separator string. | None | `split`, `maxsplit` | `{{ "hello world"\|split\|list }} -> ["hello", "world"]` |
| `string` | Converts a value into a string if it’s not one already. | None | None | `{{ value\|string }}` |
| `sum` | Sums up all the values in a sequence. | None | None | `{{ range(10)\|sum }} -> 45` |
| `title` | Converts a value to title case. | None | None | `{{ chapter.title\|title }}` |
| `tojson` | Dumps a value to JSON. | None | `ident`, `ident` | `{{ global_config\|tojson }}` |
| `trim` | Trims a value | None | `chars` | `{{ value\|trim(' ') }}` |
| `unique` | Returns a list of unique items from the given iterable. | None | `attribute`, `case_sensitive` | `{{ ['foo', 'bar', 'foobar', 'foobar']\|unique\|list }} -> ['foo', 'bar', 'foobar']` |
| `upper` | Converts a value to uppercase. | None | None | `{{ chapter.title\|upper }}` |
| `urlencode` | URL encodes a value. | None | None | `<a href="/search?{{ {"q": "my search", "lang": "fr"}\|urlencode }}">Search</a>` |
| `dateformat` | Formats a timestamp as date. | None | `format`, `tz` |  `{{ value\|dateformat(format="short", tz="Europe/Vienna") }}` |
| `datetimeformat` | Formats a timestamp as date and time. | None | `format`, `tz` | `{{ value\|datetimeformat(format="short", tz="Europe/Vienna") }}` |
| `filesizeformat` | Formats the value like a “human-readable” file size. | None | `binary` | `{{ value\|filesizeformat }}` |
| `pluralize` | Returns a plural suffix if the value is not 1, ‘1’, or an object of length 1. | None | `singular`, `plural` | `{{ entities\|pluralize("y", "ies") }}` |
| `random` :warning: Usage in a Rudder template will cause reparation at each run of the agent. | Chooses a random element from a sequence or string. | None | None | `{{ [1, 2, 3, 4]\|random }}` |
| `timeformat` | Formats a timestamp as time. | None | `format`, `tz` | `{{ value\|timeformat(format="short") }}` |
| `truncate` | Returns a truncated copy of the string. | None | `length`, `killwords`, `end`, `leeway` | `{{ "Hello World"\|truncate(length=5) }}` |
| `wordcount` | Counts the words in a string. | None | None | `{{ "Hello world!"\|wordcount }}` |
| `wordwrap` | Wrap a string to the given width. | None | `width`, `break_long_words`, `break_on_hyphens`, `wrapstring` | `{{ value\|wordwrap(width=12) }}` |
| `b64encode` | Encode a string as Base64. | None | None | `{{ plain\|b64encode }}` |
| `b64decode` | Decode a Base64 string. | None | None | `{{ encoded\|b64decode }}` |
| `basename` | Get a path’s base name. | None | None | `{{ path\|basename }}` |
| `dirname` | Get a path’s directory name. | None | None | `{{ path\|dirname }}` |
| `urldecode` | Decode percent-encoded sequences. | None | None | `{{ encoded_url\|urldecode }}` |
| `hash` | Hash of input data (SHA-1, SHA-256, SHA-512, Uses SHA-1 by default). | None | algorithm (sha-1, sha-256, sha-512) | `{{ value\|hash('sha-256') }}` |
| `quote` | Shell quoting. | None | None | `{{ value\|quote }}` |
| `regex_escape` | Escape regex chars. | None | None | `{{ re\|regex_escape }}` |
| `regex_replace` | Replace a string via regex. | None | `count` | `{{ re\|regex_replace }}` |

The following methods on basic types are provided:

- dict.get
- dict.items
- dict.keys
- dict.values
- list.count
- str.capitalize
- str.count
- str.endswith
- str.find
- str.isalnum
- str.isalpha
- str.isascii
- str.isdigit
- str.islower
- str.isnumeric
- str.isupper
- str.join
- str.lower
- str.lstrip
- str.replace
- str.rfind
- str.rstrip
- str.split
- str.splitlines
- str.startswith
- str.strip
- str.title

## Jinja2 

The Jinja2 engine is provided for backward compatibility and is only available on Linux systems.

Jinja2 behaves for the most part the same way as MiniJinja. For a complete
reference of features, see the official [Jinja2 documentation](http://jinja.pocoo.org/docs/dev/templates/).

You can extend the Jinja2 templating engine by adding custom FILTERS and TESTS in the script `/var/rudder/configuration-repository/ncf/10_ncf_internals/modules/extensions/jinja2_custom.py`

For instance, to add a filter to uppercase a string and a test if a number is odd,
you can create the file `/var/rudder/configuration-repository/ncf/10_ncf_internals/modules/extensions/jinja2_custom.py`
on your Rudder server with the following content:

```python
def uppercase(input):
    return input.upper()

def odd(value):
    return True if (value % 2) else False

FILTERS = {'uppercase': uppercase}
TESTS = {'odd': odd}
```

These filters and tests will be usable in your jinja2 templates automatically.

## Mustache

Mustache is a logic-less templating language.
For a complete reference of features, see the official [mustache specification](https://mustache.github.io/mustache.5.html).

### Syntax

This section presents some simple cases that cover what can be done with Mustache templating.

The main specificity compared to standard mustache syntax of prefixes in all expanded values:

- classes to access conditions
- vars to access all variables

#### Classes

Here is how to display content depending on conditions definition:

```
{{#classes.my_condition}}
   content when my_condition is defined
{{/classes.my_condition}}

{{^classes.my_condition}}
   content when my_condition is *not* defined
{{/classes.my_condition}}
```

Note: You cannot use condition expressions here.

### Scalar variable

Here is how to display a scalar variable value (integer, string, ...), if you have defined `variable_string("variable_prefix", "my_variable", "my_value")`:

```
{{{vars.variable_prefix.my_variable}}}
```

We use the triple `{{{ }}}` to avoid escaping html entities.

Use `{{#vars.container}}` content `{{/vars.container}}` to iterate
Use `{{{.}}}` for the current element value in iteration
Use `{{{key}}}` for the key value in current element
Use `{{{@}}}` for the current element key in iteration

To iterate over a list, for example defined with:

```
variable_iterator("variable_prefix", "iterator_name", "a,b,c", ",")
```

Use the following file:

```
{{#vars.variable_prefix.iterator_name}}
{{{.}}} is the current iterator_name value
{{/vars.variable_prefix.iterator_name}}
```

Which will be expanded as:

```
a is the current iterator_name value
b is the current iterator_name value
c is the current iterator_name value
```

To iterate over a container defined by the following json file, loaded with `variable_dict_from_file("variable_prefix", "dict_name", "path")`:

```
{
   "hosts": [
       "host1",
       "host2"
   ],
   "files": [
       {"name": "file1", "path": "/path1", "users": [ "user1", "user11" ] },
       {"name": "file2", "path": "/path2", "users": [ "user2" ] }
   ],
   "properties": {
       "prop1": "value1",
       "prop2": "value2"
   }
}
```

Use the following template:

```
{{#vars.variable_prefix.dict_name.hosts}}
{{{.}}} is the current hosts value
{{/vars.variable_prefix.dict_name.hosts}}

# will display the name and path of the current file
{{#vars.variable_prefix.dict_name.files}}
{{{name}}}: {{{path}}}
{{/vars.variable_prefix.dict_name.files}}
# Lines below will only be properly rendered in unix Nodes
# will display the users list of each file
{{#vars.variable_prefix.dict_name.files}}
{{{name}}}:{{#users}} {{{.}}}{{/users}}
{{/vars.variable_prefix.dict_name.files}}


# will display the current properties key/value pair
{{#vars.variable_prefix.dict_name.properties}}
{{{@}}} -> {{{.}}}
{{/vars.variable_prefix.dict_name.properties}}
```

Which will be expanded as:

```
host1 is the current hosts value
host2 is the current hosts value

# will display the name and path of the current file
file1: /path1
file2: /path2

# Lines below will only be properly rendered in unix Nodes
# will display the users list of each file
file1: user1 user11
file2: user2

# will display the current properties key/value pair
prop1 -> value1
prop2 -> value2
```

Note: You can use `{{#-top-}} ... {{/-top-}}` to iterate over the top level container.

### System variables

Some sys dict variables (like sys.ipv4) are also accessible as string, for example:

```
${sys.ipv4} gives 54.32.12.4
$[sys.ipv4[ethO]} gives 54.32.12.4
$[sys.ipv4[eth1]} gives 10.45.3.2
```

These variables are not accessible as dict in the templating data, but are represented as string:

```
ipv4 is a string variable in the sys dict with value 54.32.12.4
ipv4[ethO] is a string variable in the sys dict with value 54.32.12.4
ipv4 is not accessible as a dict in the template
```

To access these value, use the following syntax in your mustache templates:

```
{{{vars.sys.ipv4[eth0]}}}
```

### Top level container

The top level container can be iterated over: 

```
{{#-top-}} ... {{/-top-}}
```

It can also be rendered as a compact JSON representation

```
{{$-top-}}
```

Or rendered as a multi-line JSON representation

```
{{%-top-}}
```
