#!/bin/sh

# test properties on hooks located in src/main/resources/hooks.d

HOOKS_D="../../../main/resources/hooks.d"

HOOKS_BASE="./webapp/sources/rudder/rudder-core/src/main/resources/hooks.d"

#
# Test for correct exec rights
#
function test_is_exec {
  if ! [ -x "${HOOKS_D}/${1}" ]; then
   echo "Hooks is not executable: ${HOOKS_BASE}/${1}"
   exit 1
  fi
}

# check exec rights for hooks
test_is_exec "node-post-deletion/10-clean-policies"
test_is_exec "policy-generation-finished/50-reload-policy-file-server"
test_is_exec "policy-generation-node-ready/10-cf-promise-check"
test_is_exec "policy-generation-node-ready/90-change-perm"

#
# Test on update trigger (what happen with different combination of options)
#
HOOK="./${HOOKS_D}/policy-generation-finished/60-trigger-node-update"

# compare result with expected
function test { #expected, command result
  if [ "${1}" != "${2}" ]; then
    echo "Test fails for hook: expected: '${1}' ; got: '${2}'"
    exit 1
  fi
}

export TEST="test"
export RUDDER_NUMBER_NODES_UPDATED="5"
export RUDDER_NODE_IDS="root node-1 node-2 node-3 node-4"
export MAX_NODES="100"
export NODE_PERCENT="100"

test "root,node-1,node-2,node-3,node-4" "$(${HOOK})"

# test that the percentage is rounded above
export NODE_PERCENT="65"
test "root,node-1,node-2,node-3" "$(${HOOK})"

# test that max node is an hard limie
export MAX_NODES="2"
test "root,node-1" "$(${HOOK})"

# test that 0 percent disable the test
export NODE_PERCENT="-4"
test "" "$(${HOOK})"

# same for max limit
export NODE_PERCENT="100"
export MAX_NODES="-2"
test "" "$(${HOOK})"

# more than 100 percent is 100 percent
export NODE_PERCENT="245600"
export MAX_NODES="100"
test "root,node-1,node-2,node-3,node-4" "$(${HOOK})"
