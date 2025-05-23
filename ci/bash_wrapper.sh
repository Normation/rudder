#!/bin/sh.real
# /bin/bash

# always source our trap file as rcfile
exec /bin/bash.real --rcfile /etc/bash_trap.sh "$@"