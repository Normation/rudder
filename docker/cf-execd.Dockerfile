FROM centos:8

RUN \
        yum -y install curl

RUN \
        ln -sf /bin/true /usr/sbin/service && \
        ln -sf /bin/true /bin/systemctl && \
        ln -sf /bin/true /usr/bin/systemctl

RUN \
        curl -o rudder-setup https://repository.rudder.io/tools/rudder-setup && \
        bash rudder-setup add-repository 6.1 && \
        yum -y install rudder-agent

RUN \
        echo "server" > /var/rudder/cfengine-community/policy_server.dat && \
        mkdir  /data && \
        ln -sf /data/uuid.hive /opt/rudder/etc/uuid.hive && \
        ln -sf /data/ssl/agent.cert /opt/rudder/etc/ssl/agent.cert && \
        ln -sf /data/ppkeys/localhost.pub /var/rudder/cfengine-community/ppkeys/localhost.pub && \
        ln -sf /data/ppkeys/localhost.priv /var/rudder/cfengine-community/ppkeys/localhost.priv

COPY \
        generate-id.sh .

RUN \
        yum clean all && \
	rm -rf /var/rudder/cfengine-community/state/*

VOLUME ["/data"]

EXPOSE 443 5309

ENTRYPOINT ["/bin/bash", "-c"]

CMD ["bash generate-id.sh && rudder agent check && rudder agent inventory && /opt/rudder/bin/cf-execd --no-fork --inform"]

