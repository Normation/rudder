FROM centos:8

RUN \
        ln -sf /bin/true /usr/sbin/service && \
        ln -sf /bin/true /bin/systemctl && \
        ln -sf /bin/true /usr/bin/systemctl

# We install the relay package is it does some config for httpd
RUN \
        yum -y install epel-release && \
        yum -y install curl inotify-tools && \
        curl -o rudder-setup https://repository.rudder.io/tools/rudder-setup && \
        sh rudder-setup add-repository 6.1 && \
        yum -y install rudder-server-relay && \
        /usr/libexec/httpd-ssl-gencerts

RUN \
        ln -sf /node_id/uuid.hive /opt/rudder/etc/uuid.hive && \
        # special case as it needs to be modified
        ln -sf /httpd_conf/rudder-apache-relay-ssl.conf.mod /opt/rudder/etc/rudder-apache-relay-ssl.conf && \
        for f in rudder-networks-24.conf rudder-networks-policy-server-24.conf \
                 rudder-apache-relay-common.conf rudder-apache-relay-nossl.conf htpasswd-webdav htpasswd-webdav-initial; \
        do \
          ln -sf /httpd_conf/${f} /opt/rudder/etc/${f}; \
        done

COPY \
        httpd.sh .

RUN \
        yum clean all && \
        rm -rf /var/rudder/cfengine-community/state/*

EXPOSE 80 443

CMD ["./httpd.sh"]
