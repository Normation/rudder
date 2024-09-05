FROM python:3.11.9-bookworm
LABEL ci=rudder/relay/sources/rudder-pkg/Dockerfile

# Jenkins user with correct uid
ARG USER_ID=1000
RUN mkdir /home/jenkins ;\
    useradd -r -u $USER_ID -d /home/jenkins jenkins ;\
    chown jenkins /home/jenkins

COPY relay/sources/rudder-pkg/requirements.txt requirements.txt
ENV PYLINTHOME=/tmp/
RUN pip install -r requirements.txt
