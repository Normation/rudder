FROM centos:8
    
RUN \
     yum -y install diffutils curl && \
     curl -o rudder-setup https://repository.rudder.io/tools/rudder-setup && \
     sh rudder-setup add-repository 6.1 && \
     yum -y install rudder-server-relay && \
     /usr/libexec/httpd-ssl-gencerts && \
     yum clean all

RUN \
      mkdir /apache_conf_file && \
      ln -sf /apache_conf_file/rudder-networks-24.conf /opt/rudder/etc/rudder-networks-24.conf && \
      ln -sf /apache_conf_file/rudder-networks-policy-server-24.conf /opt/rudder/etc/rudder-networks-policy-server-24.conf && \
      ln -sf /apache_conf_file/rudder-apache-relay-ssl.conf /opt/rudder/etc/rudder-apache-relay-ssl.conf && \
      ln -sf /apache_conf_file/rudder-apache-relay-common.conf /opt/rudder/etc/rudder-apache-relay-common.conf && \
      ln -sf /apache_conf_file/rudder-apache-relay-nossl.conf /opt/rudder/etc/rudder-apache-relay-nossl.conf


EXPOSE 80 443

ENTRYPOINT ["/bin/bash", "-c"]

CMD ["/usr/sbin/apachectl -DFOREGROUND"]
