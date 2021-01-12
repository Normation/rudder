FROM centos:8

RUN \
        ln -sf /bin/true /usr/sbin/service && \
        ln -sf /bin/true /bin/systemctl && \
        ln -sf /bin/true /usr/bin/systemctl

RUN \
        yum -y install curl && \
        curl -o rudder-setup https://repository.rudder.io/tools/rudder-setup && \
        sh rudder-setup add-repository 6.1 && \
        yum -y install rudder-agent

RUN \
        ln -sf /data/uuid.hive /opt/rudder/etc/uuid.hive && \
        ln -sf /data/ssl/agent.cert /opt/rudder/etc/ssl/agent.cert && \
        ln -sf /data/ppkeys/localhost.pub /var/rudder/cfengine-community/ppkeys/localhost.pub && \
        ln -sf /data/ppkeys/localhost.priv /var/rudder/cfengine-community/ppkeys/localhost.priv

COPY cf-serverd.sh .

RUN \
        yum clean all && \
        rm -rf /var/rudder/cfengine-community/state/*

EXPOSE 5309

ENTRYPOINT ["/bin/bash", "-c"]

CMD ["./cf-serverd.sh"]
