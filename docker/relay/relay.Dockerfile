FROM centos:8

RUN \
        ln -sf /bin/true /usr/sbin/service && \
        ln -sf /bin/true /bin/systemctl && \
        ln -sf /bin/true /usr/bin/systemctl

RUN \
        yum -y install diffutils curl && \
        curl -o rudder-setup https://repository.rudder.io/tools/rudder-setup && \
        sh rudder-setup add-repository 6.1 && \
        yum -y install rudder-server-relay && \
        /usr/libexec/httpd-ssl-gencerts && \
        yum clean all 

COPY relay.sh .

EXPOSE  80 443 3030

ENTRYPOINT ["/bin/bash", "-c"]

CMD ["./relay.sh"]
