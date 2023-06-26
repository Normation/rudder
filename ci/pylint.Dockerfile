FROM python
LABEL ci=rudder/ci/pylint.Dockerfile

RUN pip install pylint
