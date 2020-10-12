FROM centos:8

RUN \
        ln -sf /bin/true /usr/sbin/service && \
        ln -sf /bin/true /bin/systemctl && \
        ln -sf /bin/true /usr/bin/systemctl

RUN \
        yum -y install curl && \
        curl -o rudder-setup https://repository.rudder.io/tools/rudder-setup && \
        sh rudder-setup add-repository 6.1 && \
        yum -y install rudder-server-relay

COPY \
        policy_server.dat /var/rudder/cfengine-community/policy_server.dat

RUN \
        # same as cf-execd for remote-run
        mkdir  /agent_certs /node_id /httpd_conf && \
        ln -sf /node_id/uuid.hive /opt/rudder/etc/uuid.hive && \
        ln -sf /relayd_conf/main.conf /opt/rudder/etc/relayd/main.conf && \
        ln -sf /agent_certs/ssl/agent.cert /opt/rudder/etc/ssl/agent.cert && \
        ln -sf /agent_certs/ppkeys/localhost.pub /var/rudder/cfengine-community/ppkeys/localhost.pub && \
        ln -sf /agent_certs/ppkeys/localhost.priv /var/rudder/cfengine-community/ppkeys/localhost.priv && \
        for f in rudder-networks-24.conf rudder-networks-policy-server-24.conf rudder-apache-relay-ssl.conf \
                 rudder-apache-relay-common.conf rudder-apache-relay-nossl.conf htpasswd-webdav htpasswd-webdav-initial; \
        do \
          cp /opt/rudder/etc/${f} /httpd_conf/${f} && \
          ln -sf /httpd_conf/${f} /opt/rudder/etc/${f}; \
        done

COPY \
        cf-serverd.sh .

RUN \
        yum clean all && \
        rm -rf /var/rudder/cfengine-community/state/*

EXPOSE 5309

CMD ["./cf-serverd.sh"]
