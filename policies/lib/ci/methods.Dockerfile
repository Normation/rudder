ARG OS=ubuntu:20.04
FROM ${OS}
LABEL ci=ncf/ci/methods.Dockerfile

RUN if type apt-get; then apt-get update && apt-get install -y wget gnupg2 make python3-jinja2 fakeroot acl git; fi ;\
    if type yum; then yum install -y wget gnupg2 make python3-jinja2 acl git; fi

# Accept all OSes
# comment to see it runs again
ENV UNSUPPORTED=y
RUN wget https://repository.rudder.io/tools/rudder-setup && sed -i "s/set -e/set -xe/" rudder-setup && sed -i "s/rudder agent inventory//" rudder-setup && sed -i "s/rudder agent health/rudder agent health || true/" rudder-setup && sh ./rudder-setup setup-agent latest
