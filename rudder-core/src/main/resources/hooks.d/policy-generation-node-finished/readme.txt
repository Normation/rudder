
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

About policy-generation-node-finished/ hooks
============================================

When/What ?
-----------

This directory contains hooks executed after policies are fully 
generated for node and made available for the node to download.

Typically, these hooks interact with external services using 
knowledged from the generated policies  (ex: send node-properties
JSON file to a third party service). 

Parameters ?
------------
Hooks parameter are passed by environment variable: 

- RUDDER_GENERATION_DATETIME: generation datetime: ISO-8601 YYYY-MM-ddTHH:mm:ss.sssZ date/time that identify that policy generation start 
- RUDDER_NODEID             : the nodeId
- RUDDER_POLICIES_DIRECTORY : new policies directory (for ex for nodes under root: /var/rudder/share/$RUDDER_NODEID/rules)
- RUDDER_AGENT_TYPE         : agent type ("cfengine-nova" or "cfengine-community")

Technically, you could infer RUDDER_NEXT_POLICIES_DIRECTORY, from RUDDER_NODEID, but it's tedious
for nodes begind relay, and it is just simpler to don't have to track what are the Rudder internal names,
which may change without notice. 


