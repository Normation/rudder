import os

def stringify(value):
    if isinstance(value, (int, bool)):
        return str(value).lower()
    if isinstance(value, str):
        return quoted(escaped(value))
    if isinstance(value, dict):
        out_value = [
            stringify(k) + ': ' + stringify(value[k]) for k in value.keys()
        ]
        return '{ ' + ', '.join(out_value) + ' }'
    if isinstance(value, list):
        out_value = [stringify(e) for e in value]
        return '[ ' + ', '.join(out_value) + ' ]'
    raise Exception('Unsupported type error: ' + str(type(value)))


def fmt_parameter(out, parameter):
    out.write(
        '@parameter.'
        + parameter['name']
        + '.description = '
        + multiline_quoted(parameter['description'])
        + '\n'
    )
    if parameter['type'] != 'string':
        # string is the default
        out.write(
            '@parameter.'
            + parameter['name']
            + '.type = '
            + quoted(parameter['type'])
            + '\n'
        )
    for constraint in parameter['constraints']:
        value = parameter['constraints'][constraint]
        # toml without a toml lib
        out_value = stringify(value)

        fmt_attribute(
            out,
            'parameter.' + parameter['name'] + '.constraints.' + constraint,
            out_value,
        )


def quoted(value):
    return '"' + value + '"'


def multiline_quoted(value):
    return "'''\n@" + '\n@'.join(value.split('\n')) + "\n@'''"


def escaped(value):
    return value.replace('\\', '\\\\').replace('"', '\\"')


def fmt_attribute(out, name, value):
    out.write('@' + name + ' = ' + value + '\n')


def generate_lib(resource_name, resource, lib_dir):
    """
    renders lib in rudder lang
    """

    if not os.path.exists(lib_dir):
        os.mkdir(lib_dir)
    file = lib_dir + '/' + resource_name + '.rd'
    out = open(file, 'w')
    fmt_attribute(out, 'format', '0')
    out.write(
        '# This file contains stubs for states directly implemented in targets languages\n'
    )
    out.write('\n\n')

    if 'description' in resource:
        fmt_attribute(
            out, 'description', multiline_quoted(resource['description'])
        )

    for parameter in resource['parameters']:
        fmt_parameter(out, parameter)
    params = [p['name'] for p in resource['parameters']]
    out.write('resource ' + resource_name + '(' + ', '.join(params) + ')\n')
    out.write('\n\n')

    for state in sorted(resource['states'], key=lambda item: item['state']):
        fmt_attribute(
            out, 'description', multiline_quoted(state['description'])
        )
        if 'documentation' in state:
            fmt_attribute(
                out, 'documentation', multiline_quoted(state['documentation'])
            )
        if 'action' in state:
            fmt_attribute(out, 'action', stringify(True))
        fmt_attribute(
            out, 'source_file', quoted(escaped(state['source_file']))
        )
        if state['deprecated'] != '':
            fmt_attribute(
                out, 'deprecated', multiline_quoted(state['deprecated'])
            )
        fmt_attribute(
            out, 'class_prefix', quoted(escaped(state['class_prefix']))
        )
        # FIXME not the same id?
        fmt_attribute(
            out, 'class_parameter_index', str(state['class_parameter_id'])
        )
        for parameter in state['parameters']:
            fmt_parameter(out, parameter)
        fmt_attribute(
            out,
            'supported_targets',
            '['
            + ', '.join('"{0}"'.format(w) for w in state['supported_agents'])
            + ']',
        )
        if 'method_aliases' in state:
            # print(state["method_aliases"])
            fmt_attribute(
                out,
                'method_aliases',
                '['
                + ', '.join(
                    '"{0}"'.format(alias) for alias in state['method_aliases']
                )
                + ']',
            )
        fmt_attribute(out, 'name', quoted(escaped(state['name'])))
        params = [p['name'] for p in state['parameters']]
        out.write(
            resource_name
            + ' state '
            + state['state']
            + '('
            + ', '.join(params)
            + ') {}\n'
        )
        out.write('\n')

    out.close()
