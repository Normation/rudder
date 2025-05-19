FROM debian:11
LABEL ci=rudder/ci/asciidoctor.Dockerfile

ARG USER_ID=1000
COPY ci/user.sh .
RUN ./user.sh $USER_ID ;\
    apt-get update && apt-get install -y asciidoctor make rsync ssh curl

RUN mv /usr/bin/rsync /usr/bin/rsync.real
COPY ci/wrap_rsync.sh /usr/bin/rsync
RUN chmod +x /usr/bin/rsync  