import re
import subprocess
import json
import os.path
import shutil
import sys
import os
import codecs
from pprint import pprint

string_type = type(u'')

### Constraint checking function

# Helper function on constraint check return
def return_constraint(result, error_message):
  if result:
    return None
  else:
    return error_message

def select( parameter_value, accepted_result):
  result = parameter_value in accepted_result
  error_message = "Value not in accepted values: "+ str(json.dumps(accepted_result))
  return return_constraint(result, error_message)

def max_length( parameter_value, max_size):
  current_length = len(parameter_value)
  result = current_length <= max_size
  error_message = "Max length is " + str(max_size)
  if max_length == 16384:
      error_message = "Fields over 16384 characters are currently not supported. If you want to edit a file, please insert your content into a file, and copy it with a file_copy_* method, or use a template"
  error_message += ". Current size is " + str(current_length)
  return return_constraint(result, error_message)

def min_length( parameter_value, min_size):
  current_length = len(parameter_value)
  result = current_length >= min_size
  error_message = "Min length is " + str(min_size) + ". Current size is " + str(current_length)
  return return_constraint(result, error_message)

def match_regex(parameter_value, regex):
  match = re.search(regex, parameter_value, re.U)
  result = match is not None
  error_message = "Must match regex "+ regex
  return return_constraint(result, error_message)

def not_match_regex(parameter_value, regex):
  result = match_regex(parameter_value, regex) is not None
  error_message = "Must not match regex "+ regex
  return return_constraint(result, error_message)

def allow_empty_string(parameter_value, allow):
  # If length more that one, this is an error
  result = allow or min_length(parameter_value,1) is None
  error_message = "Must not be empty"
  return return_constraint(result, error_message)

def allow_whitespace_string(parameter_value, allow):
  # If leading/trailing whitespace is not allowed, and we have some, this is an error
  leading = match_regex(parameter_value, r'^\s') is None
  trailing = match_regex(parameter_value, r'\s$') is None
  result = allow or ( not leading and not trailing )
  error_message = "Must not have leading or trailing whitespaces"
  return return_constraint(result, error_message)


constraints = {
    "select" : {
        "check" : select
      , "type"  : list
    }
  , "allow_whitespace_string" : {
        "check" : allow_whitespace_string
      , "type"  : bool
    }
  , "allow_empty_string" : {
        "check" : allow_empty_string
      , "type"  : bool
    }
  # use unicode for regexs, since they will be parsed as unicode ...
  , "regex" :  {
        "check" : match_regex
      , "type"  : string_type
    }
  , "not_regex":  {
        "check" : not_match_regex
      , "type"  : string_type
    }
  , "max_length" :  {
        "check" : max_length
      , "type"  : int
    }
  , "min_length" :  {
        "check" : min_length
      , "type"  : int
    }
}

variable_constraints = {
  "allow_whitespace_string" : constraints["allow_whitespace_string"]
}

default_constraint = {
    "allow_whitespace_string" : False
  , "allow_empty_string" : False
  , "max_length" : 16384
}

def check_parameter(parameter_value, parameter_constraints):
  """Checks that a parameter value is ok with the constraint of the parameter"""

  result = True
  errors = []
  constraint_set = constraints

  # Check that our value contains a variable or not
  if re.search(r'[$@][{(][$@{(a-zA-Z0-9[\]_.-]+[})]', parameter_value) is not None :
    constraint_set = variable_constraints

  for (constraint_name, constraint_value) in parameter_constraints.items():
    if constraint_name in constraint_set:
      constraint = constraint_set[constraint_name]
      constraint_check = constraint['check'](parameter_value,constraint_value)
      if constraint_check is not None:
        result = False
        errors.append(constraint_check)

  check = {'result': result, 'errors': errors}
  return check

def check_constraint_type(key, value):

  result = True
  errors = []

  if key in constraints:
    constraint = constraints[key]
    result = type(value) is constraint["type"]
    if not result:
      errors.append("expected a value of type '" + constraint["type"].__name__ + "', got '"+ str(value) +"' of type '"+ type(value).__name__  + "'")
  else:
    result = False
    errors.append("'"+ key +"' is an unknwown constraint")
  check = {'result': result, 'errors': errors}
  return check


