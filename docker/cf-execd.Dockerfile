FROM centos:8

RUN \
        ln -sf /bin/true /usr/sbin/service && \
        ln -sf /bin/true /bin/systemctl && \
        ln -sf /bin/true /usr/bin/systemctl

RUN \
        yum -y install diffutils curl && \
        curl -o rudder-setup https://repository.rudder.io/tools/rudder-setup && \
        sh rudder-setup add-repository 6.1 && \
        yum -y install rudder-agent

RUN \
        mkdir  /data && \
        ln -sf /data/uuid.hive /opt/rudder/etc/uuid.hive && \
        ln -sf /data/ssl/agent.cert /opt/rudder/etc/ssl/agent.cert && \
        ln -sf /data/ppkeys/localhost.pub /var/rudder/cfengine-community/ppkeys/localhost.pub && \
        ln -sf /data/ppkeys/localhost.priv /var/rudder/cfengine-community/ppkeys/localhost.priv

COPY \
        cf-execd.sh .

COPY \
        policy_server.dat /var/rudder/cfengine-community/policy_server.dat

RUN \
        yum clean all && \
        rm -rf /var/rudder/cfengine-community/state/*

VOLUME ["/data"]

ENTRYPOINT ["/bin/bash", "-c"]

CMD ["./cf-execd.sh"]

