FROM debian:11

ARG USER_ID=1000
COPY ci/user.sh .
RUN ./user.sh $USER_ID

RUN apt-get update && apt-get install -y asciidoctor make rsync ssh
