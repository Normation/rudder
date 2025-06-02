# Template module

The module supports the following template engines:

* Mustache
* MiniJinja which is a subset of Jinja2
* Jinja2 (available only on Linux)

## CLI

The module provides a CLI to help debug the rendering of templates.

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

### Minijinja Filters

| Name | Description | Required Args | Optional Args | Example |
| ---- | ----------- | ------------- | ------------- | ------- |
| abs  |  Returns the absolute value of a number. | None | None | `\|a - b\| = {{ (a - b)\|abs }} -> \|2 - 4\| = 2` |
| attr | Looks up an attribute. | `key` | None | `{{ value['key'] == value\|attr('key') }} -> true` |
| batch | Batch items. | `count` | `fill_with` | `{% for row in items\|batch(3, '&nbsp;') %}` |
| bool | Converts the value into a boolean value. | None | None | `{{ 42\|bool }} -> true` |
| capitalize | Convert the string with all its characters lowercased apart from the first char which is uppercased. | None | None | `{{ chapter.title\|capitalize }}` |
| default | If the value is undefined it will return the passed default value, otherwise the value of the variable: | None | `other` | `{{ my_variable\|default("my_variable was not defined") }}` |
| dictsort | Dict sorting functionality. | None | `by`, `case_sensitive`, `reverse` | `{{ dict\|sort('reverse') }}` |
| escape | Escapes a string. By default to HTML. | None | None | `{{ value\|escape }}` |
| first | Returns the first item from an iterable. | None | None |  `{{ user.email_addresses\|first\|default('no user') }}` |
| float | Converts a value into a float. | None | None | `{{ "42.5"\|float == 42.5 }} -> true` |
| groupby | Group a sequence of objects by an attribute. | None | `attribute`, `default`, `case_sensitive` | `{% for city, items in users\|groupby("city") %}` |
| indent | Indents Value with spaces | `width` | `indent_first_line`, `indent_blank_lines` | `{{ global_config\|indent(2) }}`
| int | Converts a value into an integer. | None | None | `{{ "42"\|int == 42 }} -> true` |
| items | Returns an iterable of pairs (items) from a mapping. | None | None | `{% for key, value in my_dict\|items %}` |
| join | Joins a sequence by a character | None | `joiner` | `{{ "Foo Bar Baz" \| join(", ") }} -> foo, bar, baz` |
| last | Returns the last item from an iterable. | None | None | `{% with update = updates\|last %}` |
| length | Returns the “length” of the value | None | None | ` {{ results\|length }}` |
| lines | Splits a string into lines. | None | None | `{{ "foo\nbar\nbaz"\|lines }} -> ["foo", "bar", "baz"]` |
| list | Converts the input value into a list. | None | None | `{{ value\|list }}` |
| lower | Converts a value to lowercase. | None | None | `{{ chapter.title\|lower }}` |
| map | Applies a filter to a sequence of objects or looks up an attribute. | `attribute` | None | `{{ users\|map(attribute='username')\|join(', ') }}` |
| max | Returns the largest item from an iterable. | None | None | `{{ [1, 2, 3, 4]\|max }} -> 4` |
| min | Returns the smallest item from an iterable. | None | None | `{{ [1, 2, 3, 4]\|min }} -> 1` |
| pprint | Pretty print a variable. | None | None | `{{ value\|pprint }}` |
| reject | Creates a new sequence of values that don’t pass a test. | `test_name` | None | `[1, 2, 3, 4]\|reject("odd")` |
| rejectattr | Creates a new sequence of values of which an attribute does not pass a test. | `attr` | `test_name` | `{{ users\|rejectattr("id", "even") }}` |
| replace | Does a string replace. | `from`, `to` | None | `{{ "Hello World"\|replace("Hello", "Goodbye") }} -> Goodbye World` |
| reverse | Reverses an iterable or string | None | None | `{% for user in users\|reverse %}` |
| round | Round the number to a given precision. | None | `precision` | `{{ 42.55\|round }} -> 43.0` |
| safe | Marks a value as safe. This converts it into a string. | None | None | `{{ value\|safe }}` |
| select | Creates a new sequence of values that pass a test. | None | `test_name` | `{{ [1, 2, 3, 4]\|select("odd") }} -> [1, 3]` |
| selectattr | Creates a new sequence of values of which an attribute passes a test. | `attr` | `test_name` | `{{ users\|selectattr("id", "even") }} -> returns all users with an even id` |
| slice | Slice an iterable and return a list of lists containing those items. | `count` | `fill_with` | `{% for column in items\|slice(3) %}` |
| sort | Returns the sorted version of the given list. | None | `test_name` | `{{ [1, 3, 2, 4]\|sort }} -> [4, 3, 2, 1]` |
| split | Split a string into its substrings, using split as the separator string. | None | `split`, `maxsplit` | `{{ "hello world"\|split\|list }} -> ["hello", "world"]` |
| string | Converts a value into a string if it’s not one already. | None | None | `{{ value\|string }}` |
| sum | Sums up all the values in a sequence. | None | None | `{{ range(10)\|sum }} -> 45` |
| title | Converts a value to title case. | None | None | `{{ chapter.title\|title }}` |
| tojson | Dumps a value to JSON. | None | `ident`, `ident` | `{{ global_config\|tojson }}` |
| trim | Trims a value | None | `chars` | `{{ value\|trim(' ') }}` |
| unique | Returns a list of unique items from the given iterable. | None | `attribute`, `case_sensitive` | `{{ ['foo', 'bar', 'foobar', 'foobar']\|unique\|list }} -> ['foo', 'bar', 'foobar']` |
| upper | Converts a value to uppercase. | None | None | `{{ chapter.title\|upper }}` |
| urlencode | URL encodes a value. | None | None | `<a href="/search?{{ {"q": "my search", "lang": "fr"}\|urlencode }}">Search</a>` |
| dateformat | Formats a timestamp as date. | None | `format`, `tz` |  `{{ value\|dateformat(format="short", tz="Europe/Vienna") }}` |
| datetimeformat | Formats a timestamp as date and time. | None | `format`, `tz` | `{{ value\|datetimeformat(format="short", tz="Europe/Vienna") }}` |
| filesizeformat | Formats the value like a “human-readable” file size. | None | `binary` | `{{ value\|filesizeformat }}` |
| pluralize | Returns a plural suffix if the value is not 1, ‘1’, or an object of length 1. | None | `singular`, `plural` | `{{ entities\|pluralize("y", "ies") }}` |
| random :warning: Usage in a Rudder template will cause reparation at each run of the agent. | Chooses a random element from a sequence or string. | None | None | `{{ [1, 2, 3, 4]\|random }}` |
| timeformat | Formats a timestamp as time. | None | `format`, `tz` | `{{ value\|timeformat(format="short") }}` |
| truncate | Returns a truncated copy of the string. | None | `length`, `killwords`, `end`, `leeway` | `{{ "Hello World"\|truncate(length=5) }}` |
| wordcount | Counts the words in a string. | None | None | `{{ "Hello world!"\|wordcount }}` |
| wordwrap | Wrap a string to the given width. | None | `width`, `break_long_words`, `break_on_hyphens`, `wrapstring` | `{{ value\|wordwrap(width=12) }}` |
| b64encode | Encode a string as Base64. | None | None | `{{ plain\|b64encode }}` |
| b64decode | Decode a Base64 string. | None | None | `{{ encoded\|b64decode }}` |
| basename | Get a path’s base name. | None | None | `{{ path\|basename }}` |
| dirname | Get a path’s directory name. | None | None | `{{ path\|dirname }}` |
| urldecode | Decode percent-encoded sequences. | None | None | `{{ encoded_url\|urldecode }}` |
| hash | Hash of input data (SHA-1, SHA-256, SHA-512, Uses SHA-1 by default). | None | algorithm (sha-1, sha-256, sha-512) | `{{ value\|hash('sha-256') }}` |
| quote | Shell quoting. | None | None | `{{ value\|quote }}` |
| regex_escape | Escape regex chars. | None | None | `{{ re\|regex_escape }}` |
| regex_replace | Replace a string via regex. | None | `count` | `{{ re\|regex_replace }}` |

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
- str.upper
