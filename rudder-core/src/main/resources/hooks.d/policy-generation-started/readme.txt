
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

About policy-generation-started/ hooks
========================================

When/What ?
-----------

This directory contains hooks executed when a policy generation starts. 

Typically, these hooks are used to log information about the
generation which just started or notify third parties that
shared information to node should be updated (shared-folder, etc).  


Parameters ?
------------

Hooks parameter are passed by environment variable: 

- RUDDER_GENERATION_DATETIME: generation datetime: ISO-8601 YYYY-MM-ddTHH:mm:ss.sssZ date/time that identify that policy generation. 



