FROM centos:8

RUN yum -y --setopt=tsflags=nodocs update && \
    yum -y --setopt=tsflags=nodocs install httpd 
    
RUN yum -y install diffutils curl && \
    curl -o rudder-setup https://repository.rudder.io/tools/rudder-setup && \
    sh rudder-setup add-repository 6.1 && \
    yum -y install rudder-server-relay && \
#Problem in centos8 there is an issue with local certificate so this command generate it 
    /usr/libexec/httpd-ssl-gencerts && \
    yum clean all
#we shared /apache_conf_file with the host and respectively with httpd then make a symbolick link from the origi on the host to the configuration file on httpd
RUN \
      mkdir /apache_conf_file && \
      ln -sf /apache_conf_file/rudder-networks-24.conf /opt/rudder/etc/rudder-networks-24.conf && \
      ln -sf /apache_conf_file/rudder-networks-policy-server-24.conf /opt/rudder/etc/rudder-networks-policy-server-24.conf && \
      ln -sf /apache_conf_file/rudder-apache-relay-ssl.conf /opt/rudder/etc/rudder-apache-relay-ssl.conf && \
      ln -sf /apache_conf_file/rudder-apache-relay-common.conf /opt/rudder/etc/rudder-apache-relay-common.conf && \
      ln -sf /apache_conf_file/rudder-apache-relay-nossl.conf /opt/rudder/etc/rudder-apache-relay-nossl.conf

COPY \
      run-httpd.sh .

EXPOSE 80 443
ENTRYPOINT ["/bin/bash", "-c"]

CMD ["./run-httpd.sh"]
