FROM centos:8

RUN \
       yum -y update && \
       yum -y install wget psmisc ca-certificates gnupg2 iptables && \
       wget -q https://repository.rudder.io/tools/rudder-setup && \
       chmod +x rudder-setup

RUN \
        ln -sf /bin/true /bin/systemctl && \
        ln -sf /bin/true /usr/bin/systemctl

RUN \
       bash rudder-setup add-repository 6.1-nightly && \
       yum -y install rudder-agent && \
       yum clean all


COPY serverd.sh .

EXPOSE 5309

ENTRYPOINT ["/bin/bash", "-c"]

CMD ["bash serverd.sh"]
