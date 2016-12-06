
Generality about Rudder Hooks
=============================

Hooks must be executable file, typically scripts, whatever the language
as long they are self-executable (i.e: correct #! definition). 

Hooks must be executable. All executable file will be used as hooks, and 
non executable one will be ignore (which allow to put other file in these
directory, like a readme, for example). 

Hooks are executed sequentially, in lexical order. We encourage
you to use the patter "NN-hookname", with NN a number like
"05", "20", etc. 

Errors code on hooks are interpreted as follow:
- 0     : success, no log (appart if debug one)          , continue to next hook
- 1-31  : error  , error   log in /var/log/rudder/webapp/, stop processing
- 32-63 : warning, warning log in /var/log/rudder/webapp/, continue to next hook
- 64-255: reserved for futur use case. Behavior may change without notice. 

About policy-generation-finished/ hooks
=======================================

When/What ?
-----------

This directory contains hooks executed after poplicies are fully 
generated for all nodes, and these new policies are available for
download for the node. 

Typically, these hooks are used to log information about the
generation which just happened or notify third parties that
new policies are available (for ex: cf-serverd SIGHUP) 


Parameters ?
------------

Hooks parameter are passed by environment variable: 

- RUDDER_GENERATION_DATETIME       : ISO-8601 YYYY-MM-ddTHH:mm:ss.sssZ date/time that identify that policy generation. 
- RUDDER_END_GENERATION_DATETIME   : ISO-8601 YYYY-MM-ddTHH:mm:ss.sssZ date/time when the generation ended (minus these hooks)
- RUDDER_NODEIDS                   : space separated list of node id updated during the process, or the empty string
                                     if no nodes were updated. 
- RUDDER_NUMBER_NODES_UPDATED      : integer >= 0; number of nodes updated (could be found by counting $RUDDER_NODEIDS)
- RUDDER_ROOT_POLICY_SERVER_UPDATED: 0 if root was updated, anything else if not



