FROM debian:11
LABEL ci=rudder/ci/shellcheck.Dockerfile

RUN apt-get update && apt-get install -y shellcheck
