#!/usr/bin/python

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

import sys
import os
from optparse import OptionParser

import jinja2
from jinja2 import Environment, FileSystemLoader, StrictUndefined

from distutils.version import StrictVersion

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
