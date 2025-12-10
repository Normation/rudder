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
# It will be copied into ${workdir}/modules by ncf initialization process.
# Can be extended with extra filters and tests with script call jinja2_custom.py
# loaded from /var/rudder/ncf/local/10_ncf_internals/modules/extensions/
# Source of this script need to be /var/rudder/configuration-repository/ncf/10_ncf_internals/modules/extensions on the Rudder Server

import sys
import os
from optparse import OptionParser

import jinja2
from jinja2 import Environment, FileSystemLoader, StrictUndefined

try:
    import simplejson as json
except ImportError:
    import json

PY3 = sys.version_info > (3,)

def render(args):
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

    # keep_trailing_newline appeared in jinja 2.7, see http://jinja.pocoo.org/docs/dev/api/
    # we add a case for this as it can be really important in configuration management context
    if [int(x) for x in jinja2.__version__.split(".")[0:2]] >= [2, 7]:
        env = Environment(
            loader=FileSystemLoader(os.path.dirname(template_path)),
            keep_trailing_newline=True
        )
    else:
        env = Environment(
            loader=FileSystemLoader(os.path.dirname(template_path)),
        )

    env.undefined = StrictUndefined

    # Register custom filters
    sys.path.append(os.path.join(os.path.dirname(__file__), "..", "extensions"))
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

    if PY3:
      output = env.get_template(os.path.basename(template_path)).render(data)
    else:
      output = env.get_template(os.path.basename(template_path)).render(data).encode("utf-8")

    sys.stdout.write(output)

def main():
    parser = OptionParser(
        usage="usage: %prog <template_file> [data_file]",
    )
    _, args = parser.parse_args()

    if len(args) not in [1, 2]:
        parser.print_help()
        sys.exit(1)

    render(args)
    sys.exit(0)

if __name__ == '__main__':
    main()
