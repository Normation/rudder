#!/usr/bin/python3
"""
Lib used by the ncf sanity tests
Defines the Method class, and make the interactions
with the ncf python api to avoid using it in tests.
"""

import os
import sys
import re
import codecs
from shutil import which
DIRNAME = os.path.dirname(os.path.abspath(__file__))
GIT_ROOT = DIRNAME + '/../../..'
sys.path.insert(0, GIT_ROOT + '/tests/')
print(GIT_ROOT)
import ncf

ncf.CFPROMISES_PATH = which("cf-promises")
NCF_TREE = os.getenv('NCF_TREE')
NCF_TREE = GIT_ROOT + '/tree'
#NCF_TREE = os.getenv('NCF_TREE')

PROMISE_TYPES = [
    "meta",
    "vars",
    "defaults",
    "classes",
    "users",
    "files",
    "packages",
    "guest_environments",
    "methods",
    "processes",
    "services",
    "commands",
    "storage",
    "databases",
    "report"
]

def canonify(string):
    """
    Canonify a given string
    """
    regex = re.compile(r'[^a-zA-Z0-9]')
    return re.sub(regex, '_', string)

class Method:
    """
    Represent an ncf method, making it easier to manipulate
    """
    def __init__(self, path):
        self.path = path
        self.path_basename = os.path.basename(self.path).split('.')[0]
        self.raw_content = self.get_raw_content()
        self.content = self.get_bundle_content()
        self.metadata = ncf.parse_generic_method_metadata(self.raw_content)["result"]

    def get_raw_content(self):
        """
        Return raw content of the whole file defining the method
        """
        with codecs.open(self.path, encoding="utf-8") as file_descriptor:
            content = file_descriptor.read()
        return content

    def get_bundle_content(self):
        """
        Return content of the method, which is the cfengine code without comments
        """
        content = []
        raw = self.get_raw_content()
        for line in raw.splitlines():
            match = re.match(r"^\s*#.*$", line, flags=re.UNICODE)
            if not match:
                content.append(line)
        return content

    def get_bundles(self):
        """
        Return each bundles called in the method content
        """
        matches = []
        for line in self.content:
            match = re.match(r'\s*bundle agent ([a-zA-Z_]+)\s*.*$', line, flags=re.UNICODE)
            if match:
                matches.append(match.group(1))
        return matches

    def get_bundle_name(self):
        """
        Return the bundle name of the method
        """
        return self.get_bundles()[0]

    def get_promise_types(self):
        """
        Return all promise types used in the method
        """
        matches = []
        promises_regex = '(' + '|'.join(PROMISE_TYPES) + ')'
        for line in self.content:
            match = re.match(r'^\s*%s:$'%promises_regex, line, flags=re.UNICODE)
            if match:
                matches.append(match.group(1))
        return matches


def get_methods():
    """
    Return an array of Method object containing all methods defined in the
    tree/30_generic_methods of the repo
    """
    gms = []
    methods_folder = NCF_TREE + '/30_generic_methods/'
    # We do not parse non method files (starting by _) since the ncf lib can not parse them
    # They are needed if we ever want to migrate the other tests to python
    filenames = [
        methods_folder + x for x in os.listdir(methods_folder)
        if x.endswith('.cf') and not x.startswith('_')
    ]

    for method_file in filenames:
        gms.append(Method(method_file))
    return gms

def test_pattern_on_file(filename, pattern):
    """
    Return the result of a search of a pattern on a whole file
    """
    with codecs.open(filename, encoding="utf-8") as file_descriptor:
        content = file_descriptor.read()
        return re.search(pattern, content)
