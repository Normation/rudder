FROM centos:8

RUN \
        ln -sf /bin/true /usr/sbin/service && \
        ln -sf /bin/true /bin/systemctl && \
        ln -sf /bin/true /usr/bin/systemctl

RUN \
        yum -y install epel-release && \
        yum -y install sudo curl inotify-tools && \
        curl -o rudder-setup https://repository.rudder.io/tools/rudder-setup && \
        sh rudder-setup add-repository 6.1 && \
        yum -y install rudder-server-relay

RUN \
        ln -sf /node_id/uuid.hive /opt/rudder/etc/uuid.hive && \
        ln -sf /relayd_conf/main.conf.mod /opt/rudder/etc/relayd/main.conf

COPY \
        relayd.sh .

RUN \
        yum clean all && \
        rm -rf /var/rudder/cfengine-community/state/*

EXPOSE 3030

CMD ["./relayd.sh"]
