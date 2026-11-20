# vim: syntax=python

#####################################################################################
# Copyright 2016 Normation SAS
#####################################################################################
#
# This program is free software: you can redistribute it and/or modify
# it under the terms of the GNU General Public License as published by
# the Free Software Foundation, Version 3.
#
# This program is distributed in the hope that it will be useful,
# but WITHOUT ANY WARRANTY; without even the implied warranty of
# MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
# GNU General Public License for more details.
#
# You should have received a copy of the GNU General Public License
# along with this program.  If not, see <http://www.gnu.org/licenses/>.
#
#####################################################################################

# Script for jinja2 templating.
# Needs package python-jinja2 present on the system.
# Embedded in, and run by, the template module (rudder-module-template).
# Can be extended with extra filters and tests in a jinja2_custom.py module, loaded from the
# directory given with --extensions-dir (the module passes the agent's modules/extensions
# directory). Its source is /var/rudder/configuration-repository/ncf/10_ncf_internals/modules/extensions
# on the Rudder server.

import sys
import os
from optparse import OptionParser

import jinja2
from jinja2 import Environment, FileSystemLoader, StrictUndefined
from jinja2.sandbox import SandboxedEnvironment

try:
    import simplejson as json
except ImportError:
    import json

PY3 = sys.version_info > (3,)

def register_extensions(env, extensions_dir):
    sys.path.append(extensions_dir)
    # importlib was introduced in 3.4 and pkgutil deprecated in 3.12 in favor of it
    try:
        import importlib.util
        custom_filters = importlib.util.find_spec("jinja2_custom") is not None
    except:
        import pkgutil
        custom_filters = pkgutil.find_loader('jinja2_custom') is not None

    if custom_filters:
        import jinja2_custom # pylint: disable=import-error
        if hasattr(jinja2_custom, 'FILTERS'):
            from jinja2_custom import FILTERS as CUSTOM_FILTERS # pylint: disable=import-error
            env.filters.update(CUSTOM_FILTERS)
        if hasattr(jinja2_custom, 'TESTS'):
            from jinja2_custom import TESTS as CUSTOM_TESTS # pylint: disable=import-error
            env.tests.update(CUSTOM_TESTS)
    sys.path.pop()

def render(args, sandboxed, inline, extensions_dir):
    if len(args) == 1:
        data = sys.stdin.read()
    else:
        path = os.path.join(os.getcwd(), os.path.expanduser(args[1]))
        if PY3:
            data_file = open(path, encoding='utf-8')
        else:
            data_file = open(path)
        data = data_file.read()
        data_file.close()

    template_path = os.path.abspath(args[0])

    try:
        data = json.loads(data)
    except ValueError as err:
        sys.stderr.write(str(err))
        sys.exit(1)

    # Sandboxed: SandboxedEnvironment blocks Python-internals access (no code
    # execution). A filesystem loader (for include/import/extends) is used only
    # for trusted file-based templates, rooted at the template's own directory.
    # Inline templates have no real directory (theirs is a private temp dir),
    # so they get no loader.
    use_loader = not sandboxed and not inline
    env_class = SandboxedEnvironment if sandboxed else Environment
    loader = FileSystemLoader(os.path.dirname(template_path)) if use_loader else None

    # keep_trailing_newline appeared in jinja 2.7, see http://jinja.pocoo.org/docs/dev/api/
    # we add a case for this as it can be really important in configuration management context
    if [int(x) for x in jinja2.__version__.split(".")[0:2]] >= [2, 7]:
        env = env_class(
            loader=loader,
            keep_trailing_newline=True
        )
    else:
        env = env_class(
            loader=loader,
        )

    env.undefined = StrictUndefined

    # Register custom filters
    if extensions_dir is not None:
        register_extensions(env, extensions_dir)

    if use_loader:
        template = env.get_template(os.path.basename(template_path))
    else:
        # No loader, so load straight from content.
        with open(template_path, encoding='utf-8') if PY3 else open(template_path) as f:
            template = env.from_string(f.read())

    if PY3:
      output = template.render(data)
    else:
      output = template.render(data).encode("utf-8")

    sys.stdout.write(output)

def main():
    parser = OptionParser(
        usage="usage: %prog [--sandboxed] [--inline] [--extensions-dir DIR] <template_file> [data_file]",
    )
    parser.add_option(
        "--sandboxed",
        action="store_true",
        default=False,
        help="restrict untrusted templates: sandboxed env, no filesystem access",
    )
    parser.add_option(
        "--inline",
        action="store_true",
        default=False,
        help="template is inline (no file loader for include/import/extends)",
    )
    parser.add_option("--extensions-dir", dest="extensions_dir", default=None,
                      help="directory containing an optional jinja2_custom.py module")
    options, args = parser.parse_args()

    if len(args) not in [1, 2]:
        parser.print_help()
        sys.exit(1)

    render(args, options.sandboxed, options.inline, options.extensions_dir)
    sys.exit(0)

if __name__ == '__main__':
    main()
