from flask import Flask

app = Flask(__name__)
from ncf_api_flask_app import views
