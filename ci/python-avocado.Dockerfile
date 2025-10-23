FROM debian:13
LABEL ci=ncf/ci/python.Dockerfile

ARG USER_ID=1000
COPY ci/user.sh .
RUN ./user.sh $USER_ID ;\
    apt-get update && apt-get install -y python3-jinja2 git wget gnupg2 make pipx ;\
    pipx install avocado-framework pylint ;\
    pipx ensurepath --global

# Accept all OSes
ENV UNSUPPORTED=y
RUN wget https://repository.rudder.io/tools/rudder-setup && sed -i "s/set -e/set -xe/" rudder-setup && sed -i "s/rudder agent inventory//" rudder-setup && sed -i "s/rudder agent health/rudder agent health || true/" rudder-setup && sh ./rudder-setup setup-agent latest
