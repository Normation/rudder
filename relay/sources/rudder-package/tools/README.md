## GPG keyring

Built with:

```
rm -f rudder_plugins_key.gpg
wget https://repository.rudder.io/apt/rudder_apt_key.pub
gpg --no-default-keyring --keyring ./rudder_plugins_key.gpg --import rudder_apt_key.pub 
```
