# rudder-relay container

This container provides a full Rudder relay running unprivileged.
Images are currently based on CentOS 8 and install latest Rudder using rudder-setup.

This should be considered in technical preview state, and may evolve in the future.

## Usage

### Build

Specify a version to install with:

```bash
docker build --build-arg VERSION=6.2.0 .
```

### Configuration

The container is configurable with env variables:

* `RUDDER_RELAY_ID`: agent id
* `RUDDER_RELAY_PRIVKEY`: agent private key
* `RUDDER_RELAY_CERTIFICATE`: agent certificate
* `RUDDER_RELAY_SERVER`: hostname/IP of the server, default is "rudder"
* `RUDDER_RELAY_SERVER_PUBKEY`: server public key

The env variables have priority over persisted values.
If not provided at first start of the container, the id and keys will be generated.

The id, keys and certificate can be persisted by making `/var/rudder/cfengine-community/ppkeys/` persistent.

### Docker

Start with:

```bash
docker run -p443:443 -p5309:5309
```

It is currently not possible to modify the ports used by the container.

### Nomad

We provide a sample Nomad configuration in `rudder-relay.nomad`.

### Upgrade

To upgrade, you need to keep the `ppkeys` volume and replace the container by a new one.

## Development

It is a pretty standard agent setup, except for id/key management, because we want to
be able to specify those at runtime, and to persist them.

Because of this we duplicate the id/key/cert generation code in the container start script.

### Service management

We use the `systemctl3.py` systemctl replacement that allows us to rely on our systemd
units to start the processes in the container.

We patch it to remove warnings for directives in unit files that are ignored.

### TODO

* Make HTTPS certs configurable from env vars
* Don't rely on rudder-setup for reproducible container builds
* Check and document the list of folders that need to be writable:

  * /var/rudder/cfengine-community/
  * /var/rudder/ncf
  * /usr/share/ncf/tree
  * /var/rudder/configuration-repository/ncf
  * /var/rudder/share
  * /var/rudder/configuration-repository/shared-files
  * /var/rudder/lib/relay
  * /var/rudder/lib/ssl
  * /var/rudder/shared-files
  * /var/rudder/tmp
  * /var/rudder/modified-files
  * /var/backup/rudder
  * /opt/rudder/var/fusioninventory
  * /var/rudder/inventories
  * /var/rudder/reports
  * /tmp
  * /etc/cron.d
  * /etc/logrotate.d
  * /var/log
  * /var/log/httpd
  * /var/log/rudder/apache2
  * /run/httpd
