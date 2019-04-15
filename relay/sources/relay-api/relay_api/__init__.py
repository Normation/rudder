from flask import Flask

app = Flask(__name__)

from relay_api import views
