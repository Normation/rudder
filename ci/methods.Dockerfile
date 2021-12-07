ARG OS=ubuntu:20.04
FROM ${OS}

RUN if type apt-get; then apt-get update && apt-get install -y wget gnupg2 make python3-jinja2 fakeroot acl git; fi
RUN if type yum; then yum install -y wget gnupg2 make python3-jinja2 acl git; fi

# Accept all OSes
ENV UNSUPPORTED=y
RUN wget https://repository.rudder.io/tools/rudder-setup && sh ./rudder-setup setup-agent latest || true
