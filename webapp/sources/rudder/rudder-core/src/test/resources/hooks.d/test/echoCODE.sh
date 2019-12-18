#!/bin/bash

#this one looks for CODE env var and use it as return code

RESULT=${CODE:= 17} # because
exit ${RESULT}
