FROM rockylinux:9
ARG VERSION=latest
EXPOSE 443 5309

RUN \
    # disable service management during package installation
    cp /usr/bin/true /usr/bin/systemctl && \
    yum -y install curl && \
    curl --silent --output rudder-setup https://repository.rudder.io/tools/rudder-setup && \
    sh rudder-setup add-repository $VERSION && \
    yum -y install rudder-server-relay && \
    # sed -i '1 a set -x' /opt/rudder/share/commands/agent-check
    # needed for httpd
    /usr/libexec/httpd-ssl-gencerts

COPY rudder-relay.sh .

# https://github.com/gdraheim/docker-systemctl-replacement/blob/1bb5768abbf68245b7b6a8bd9d6ac4df4d78044f/files/docker/systemctl3.py
COPY systemctl3.py /usr/bin/systemctl

# Ensure scripts are executable
RUN chmod +x rudder-relay.sh /usr/bin/systemctl

RUN systemctl enable rudder-cf-execd rudder-cf-serverd httpd rudder-relayd

RUN \
    yum clean all && \
    rm -rf /var/rudder/cfengine-community/state/* && \
    # remove keys/id from image
    rm -rf /var/rudder/cfengine-community/ppkeys/* && \
    rm -f /opt/rudder/etc/uuid.hive && \
    rm -f /opt/rudder/etc/ssl/agent.cert && \
    # enforce bootstrap policies in container
    cp -r /opt/rudder/share/bootstrap-promises/* /var/rudder/cfengine-community/inputs/

CMD ["./rudder-relay.sh"]
