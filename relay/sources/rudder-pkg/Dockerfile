FROM python

# Jenkins user with correct uid
ARG USER_ID=1000
RUN mkdir /home/jenkins
RUN useradd -r -u $USER_ID -d /home/jenkins jenkins
RUN chown jenkins /home/jenkins

COPY relay/sources/rudder-pkg/requirements.txt requirements.txt
RUN pip install -r requirements.txt
