FROM debian:13
LABEL ci=rudder/ci/asciidoctor.Dockerfile

ARG USER_ID=1000
COPY ci/user.sh .
RUN ./user.sh $USER_ID ;\
    apt-get update && apt-get install -y asciidoctor make rsync ssh
