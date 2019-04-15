# Import core modules
import sys

# Set up paths
api_path = '/opt/rudder/share/relay-api'
virtualenv_path = '/opt/rudder/share/relay-api/flask'

# Virtualenv initialization
activate_this = virtualenv_path + '/bin/activate_this.py'
execfile(activate_this, dict(__file__=activate_this))

# Append ncf API path to the current one
sys.path.append(api_path)

# Launch
from relay_api import app as application
