import os
import codecs
import re

mandatory_metadata_params = [
    'name',
    'description',
    'parameter',
    'bundle_name',
    'bundle_args',
    'class_prefix',
    'class_parameter',
]

# "new_method_name": [ "old_method_names", ..."]
deprecated_methods = {}


def get_deprecated_methods():
    return deprecated_methods


# Metadata must be generated from the ncf.py from the ncf repository
class State:
    def __init__(self, metadata, source, dsc_filenames):
        for param in mandatory_metadata_params:
            assert param in metadata.keys(), (
                'Missing key ' + param + ' in method ' + source
            )

        ####### Hacks
        bundle_name = metadata['bundle_name']

        # variable_string_escaped and variable_string_match are using a complete variable name and
        # not a coupled prefix + name
        if bundle_name in ['variable_string_escaped', 'variable_string_match']:
            metadata['parameter'] = [
                {
                    'name': 'prefix',
                    'description': 'The prefix of the variable name',
                    'constraints': {},
                    'type': 'string',
                }
            ] + metadata['parameter']
            metadata['bundle_args'].insert(0, 'prefix')
            self.class_parameter_id = 0

        # audit_from_osquery does not belong to an audit resource, but in sysinfo
        if bundle_name == 'audit_from_osquery':
            metadata['bundle_name'] = 'sysinfo_query'
            deprecated_methods['sysinfo_query'] = [bundle_name]

        # permissions does not have any state, change it to permissions_value
        if bundle_name == 'permissions':
            metadata['bundle_name'] = 'permissions_value'
            deprecated_methods['permissions_value'] = [bundle_name]

        # monitoring methods need a state, change it to "<bundle_name>_present"
        if metadata['bundle_name'] in [
            'monitoring_template',
            'monitoring_parameter',
        ]:
            metadata['bundle_name'] = bundle_name + '_present'
            deprecated_methods[metadata['bundle_name']] = [bundle_name]

        # some resources use more than 1 word for their name, we have no proper way to detect it atm
        multi_words_resource_names = {
            "monitoring_": 2,
            "kernel_module": 2,
            "registry_": 2,
            "http_request": 2,
            "dsc_mof_file_": 3
        }
        self.shift = 1
        for key, value in multi_words_resource_names.items():
            if metadata['bundle_name'].startswith(key):
                self.shift = value

        # some resources use more than 1 parameters, and we have no proper way to detect it atm
        multi_resource_parameters = {
            'http_request_': 2,
            'sharedfile_': 2,
            'variable_': 2,
            'registry_entry_': 2,
        }
        self.resource_id = 1
        for key, value in multi_resource_parameters.items():
            if metadata['bundle_name'].startswith(key):
                self.resource_id = value

        ####### END HACKS

        self.metadata = metadata
        self.source = source
        self.class_parameter = metadata['class_parameter']
        self.agents_support = self.get_agents_support(dsc_filenames)
        if 'rename' in self.metadata:
            if self.metadata['rename'] in deprecated_methods:
                deprecated_methods[self.metadata['rename']].append(
                    self.metadata['bundle_name']
                )
            else:
                deprecated_methods[self.metadata['rename']] = [
                    self.metadata['bundle_name']
                ]
        try:
            if not hasattr(self, 'class_parameter_id'):
                self.class_parameter_id = metadata['bundle_args'].index(
                    self.class_parameter
                )
        except BaseException:
            raise Exception(
                "The class_parameter name '"
                + self.class_parameter
                + "' does not seem to match any of the bundle's parameters in"
                + self.source
            )

        # Since parameters renaming is not possible, we use @parameter_rename new_name old_name
        # to rename outdated parameters.
        if 'parameter_rename' in self.metadata:
            for renaming in self.metadata['parameter_rename']:
                old_name = renaming['old']
                new_name = renaming['new']
                for i in range(0, len(self.metadata['parameter']) - 1):
                    if self.metadata['parameter'][i]['name'] == old_name:
                        self.metadata['parameter'][i]['name'] = new_name

    def get_agents_support(self, dsc_filenames):
        agents = []
        basefile = os.path.basename(self.source)
        for dsc_method in dsc_filenames:
            if dsc_method.endswith(
                '/' + os.path.splitext(basefile)[0] + '.ps1'
            ):
                agents.append('dsc')
                break
        match_empty_bundle = (
            r'\n\s*bundle\s+agent\s+'
            + self.metadata['bundle_name']
            + r'\b.*?\{\s*\}'
        )

        with codecs.open(self.source, encoding='utf-8') as file_descriptor:
            content = file_descriptor.read()
        if not re.search(match_empty_bundle, content, re.DOTALL):
            agents.append('cf')
        return agents

    def to_dict(self):
        (
            resource_parameters,
            state_parameters,
        ) = self.get_state_and_resource_parameters()
        (resource_name, state_name) = self.get_state_and_resource_name()
        data = {
            'class_parameter': self.metadata['class_parameter'],
            'class_parameter_id': self.class_parameter_id,
            'class_prefix': self.metadata['class_prefix'],
            'description': self.metadata['description'],
            'documentation': self.metadata.get('documentation', ''),
            'method': self.metadata['bundle_name'],
            'name': self.metadata['name'],
            'deprecated': self.metadata.get('deprecated', ''),
            'parameters': state_parameters,
            'resource_parameters': resource_parameters,
            'source_file': self.source,
            'resource': resource_name,
            'state': state_name,
            'supported_agents': self.agents_support,
        }
        if 'action' in self.metadata:
            data['action'] = self.metadata['action']
        return data

    def get_state_and_resource_name(self):
        split = self.metadata['bundle_name'].split('_')
        resource_name = '_'.join(split[:self.shift])
        state_name = '_'.join(split[self.shift:])
        return (resource_name, state_name)

    def get_state_and_resource_parameters(self):
        resource_id = self.resource_id
        resource_parameters = []
        state_parameters = []
        cleaned_up_parameters = clean_constraints(
            self.metadata['parameter']
        )
        for param_name in self.metadata['bundle_args']:
            try:
                param = next(
                    x for x in cleaned_up_parameters if x['name'] == param_name
                )
                if param_name == self.class_parameter:
                    resource_parameters.append(param)
                    resource_id -= 1
                elif resource_id > 1:
                    resource_parameters.append(param)
                else:
                    state_parameters.append(param)
            except BaseException:
                raise Exception(
                    'Could not find param '
                    + param_name
                    + ' in cleanup_parameters for method '
                    + self.source
                    + '\n'
                    + cleaned_up_parameters
                )
        return (resource_parameters, state_parameters)

def clean_constraints(params):
    """
    param input must be of the form:
    [
      {
        "name": "command",
        "description": "Command to run",
        "constraints": {
          "allow_whitespace_string": False,
          "allow_empty_string": False,
          "max_length": 16384
        },
        "type": "string"
      },
      ...
    ]
    """
    data = []
    for i in params:
        constraints = {}
        for constraint_name, value in i['constraints'].items():
            if value is not False and constraint_name != 'max_length':
                constraints[constraint_name] = value
        i['constraints'] = constraints
        data.append(i)
    return data
