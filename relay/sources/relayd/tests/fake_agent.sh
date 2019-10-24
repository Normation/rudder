#!/bin/sh
echo -n "$@" > ./target/tmp/api_test.txt
sleep 0.5
echo "OK"
sleep 0.3
echo "END"
