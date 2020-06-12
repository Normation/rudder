FROM centos:8

RUN \
        ln -sf /bin/true /usr/sbin/service && \
        ln -sf /bin/true /bin/systemctl && \
        ln -sf /bin/true /usr/bin/systemctl

RUN \
        yum -y install diffutils curl && \
        curl -o rudder-setup https://repository.rudder.io/tools/rudder-setup && \
        sh rudder-setup add-repository 6.1 && \
        yum -y install rudder-server-relay

RUN \
        mkdir  /data /apache_conf_file && \
        ln -sf /data/uuid.hive /opt/rudder/etc/uuid.hive && \
        ln -sf /data/ssl/agent.cert /opt/rudder/etc/ssl/agent.cert && \
        ln -sf /data/ppkeys/localhost.pub /var/rudder/cfengine-community/ppkeys/localhost.pub && \
        ln -sf /data/ppkeys/localhost.priv /var/rudder/cfengine-community/ppkeys/localhost.priv
COPY \
        script.sh .

RUN \
        sh script.sh && \
        ln -sf /apache_conf_file/rudder-networks-24.conf /opt/rudder/etc/rudder-networks-24.conf && \
        ln -sf /apache_conf_file/rudder-networks-policy-server-24.conf /opt/rudder/etc/rudder-networks-policy-server-24.conf && \
        ln -sf /apache_conf_file/rudder-apache-relay-ssl.conf /opt/rudder/etc/rudder-apache-relay-ssl.conf && \
        ln -sf /apache_conf_file/rudder-apache-relay-common.conf /opt/rudder/etc/rudder-apache-relay-common.conf && \
        ln -sf /apache_conf_file/rudder-apache-relay-nossl.conf /opt/rudder/etc/rudder-apache-relay-nossl.conf

COPY \
        cf-execd.sh .

RUN \
        yum clean all && \
        rm -rf /var/rudder/cfengine-community/state/*


ENTRYPOINT ["/bin/bash", "-c"]

CMD ["./cf-execd.sh"]
