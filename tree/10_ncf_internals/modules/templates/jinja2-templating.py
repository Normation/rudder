#!/bin/sh
# vim: syntax=python
''':'
# First try to run this script with python3, else run with python
command -v python3 >/dev/null 2>/dev/null  \
    && exec python3 "$0" "$@" \
    || exec python  "$0" "$@"
'''

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

from distutils.version import StrictVersion
import pkgutil

try:
    import simplejson as json
except ImportError:
    import json

PY3 = sys.version_info > (3,)

def render(opts, args):
    if len(args) == 1:
        data = sys.stdin.read()
    else:
        path = os.path.join(os.getcwd(), os.path.expanduser(args[1]))
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
    if StrictVersion(jinja2.__version__) >= StrictVersion("2.7"):
        env = Environment(
            loader=FileSystemLoader(os.path.dirname(template_path)),
            keep_trailing_newline=True
        )
    else:
        env = Environment(
            loader=FileSystemLoader(os.path.dirname(template_path)),
        )

    if opts.strict:
        env.undefined = StrictUndefined

    # Register customs
    sys.path.append(os.path.join(os.path.dirname(__file__), "..", "extensions"))
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

    output = env.get_template(os.path.basename(template_path)).render(data)

    if not PY3:
        output = output.decode("utf-8")

    sys.stdout.write(output)

def main():
    parser = OptionParser(
        usage="usage: %prog [options] <template_file> [data_file]",
    )
    parser.add_option(
        '--strict',
        help='fail when using undefined variables in the template',
        dest='strict', action='store_true')
    opts, args = parser.parse_args()

    if len(args) not in [1, 2]:
        parser.print_help()
        sys.exit(1)

    render(opts, args)
    sys.exit(0)

if __name__ == '__main__':
    main()
