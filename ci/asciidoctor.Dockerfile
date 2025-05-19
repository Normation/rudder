FROM debian:11
LABEL ci=rudder/ci/asciidoctor.Dockerfile

ARG USER_ID=1000
COPY ci/user.sh .
RUN ./user.sh $USER_ID ;\
    apt-get update && apt-get install -y asciidoctor make rsync ssh curl

RUN mv /usr/local/bin/rsync /usr/local/bin/rsync.real
COPY ci/wrap_rsync.sh /usr/local/bin/rsync
RUN chmod +x /usr/local/bin/rsync  

# Make it executable
RUN chmod +x /usr/local/bin/rsync