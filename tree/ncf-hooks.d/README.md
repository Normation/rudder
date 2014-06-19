# ncf hooks

When ncf API is writing/deleting a new file in a ncf directory, you can add your own behavior by adding hooks in that directory

Hooks are scripts (Python, shell) that are executed before/after an action was made by ncf API

Scripts should be named using this pattern: "{pre,post}.[action_name].*", where pre/post lets you choose whether this should be run before or after the API action, and the [action-name] is one of the commands in the ncf API. Commands used are: delete_technique, create_technique, update_technique, write_technique (alias for both create and update).
For example: pre.create_technique.send_email.py will run this script before creating a new technique.

Note: you can append one of the following suffixes to the file name prevent a hook from running: ex, example, disable, disabled

 
