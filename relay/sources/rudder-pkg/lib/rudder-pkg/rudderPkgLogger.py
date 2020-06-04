import os, logging, sys
import logging.handlers

LOG_PATH = "/var/log/rudder/rudder-pkg/rudder-pkg.log"

"""
    Start two different loggers:
      -one for the output           => stdoutHandler
      -another one for the log file => fileHandler
    They have the same root, so logging is common to both handler,
    but they will each log what they should based on their log level.
"""
def startLogger(logLevel):
    root = logging.getLogger("rudder-pkg")
    root.setLevel(logging.DEBUG)

    # log file handler
    if not os.path.isdir(os.path.dirname(LOG_PATH)):
      os.makedirs(os.path.dirname(LOG_PATH))
    fileHandler = logging.handlers.RotatingFileHandler(filename=LOG_PATH,maxBytes=1000000,backupCount=1)
    fileHandler.setLevel(logging.DEBUG)
    fileFormatter = logging.Formatter('%(asctime)s - %(levelname)s - %(message)s')
    fileHandler.setFormatter(fileFormatter)

    # stdout handler
    stdoutHandler = logging.StreamHandler(sys.stdout)
    stdoutFormatter = logging.Formatter('%(message)s')
    stdoutHandler.setFormatter(stdoutFormatter)
    if logLevel == 'INFO':
        stdoutHandler.setLevel(logging.INFO)
    elif logLevel == 'DEBUG':
        stdoutHandler.setLevel(logging.DEBUG)
    else:
        logging.error("unknow loglevel %s"%(logLevel))
        exit(1)


    root.addHandler(stdoutHandler)
    root.addHandler(fileHandler)
