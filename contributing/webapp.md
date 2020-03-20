# How to setup your development's environment for Rudder's web application

## Synopsis
This is a **straightforward guide** aims to setup all tools and settings on a Linux OS, to be able to modify Rudder source code and to test it locally. Please follow this guide step by step. If you want to learn more about the tools feel free to read the document link to it !

>Note that the NCF Api and the Technique Editor are not part of the webapp and will require further steps to setup. (Link and explanations provided below in the relative section)

## Requirements for your machine
- Vagrant
- Virtual Box and Oracle VM VirutalBox Extension pack
- IntelliJ
- OpenJDK 8 at least
- git
- netstat

## Table of Contents  
- [Synopsis](#synopsis)
- [Requirements](#requirements)
- [Part 0 : Installation of required packages and softwares](#part-0---installation-of-required-packages-and-softwares)
  * [Useful packages](#useful-packages)
  * [VirtualBox installation](#virtualBox-installation)
  * [Vagrant installation](#vagrant-installation)
  * [Intellij Idea Community installation](#intellijIC-installation)
  * [ldap apache directory studio installation](#ldap-apache-directory-studio-installation)
  * [Elm installation](#elm-installation)
  * [Rudder Test framework (RTF)](#rtf-installation)
- [Part 1 : Setup the local environment and the dev box](#part-1---setup-the-local-environment-and-the-dev-box)
  * [Install Rudder Test framework (RTF)](#install-rudder-test-framework--rtf-)
  * [Create a platform description file](#create-a-platform-description-file)
  * [Setup the local environment](#setup-the-local-environnement)
    + [Create potentially missing files and add permission](#create-potentially-missing-files-and-add-permission)
    + [Synchronize techniques](#synchronize-techniques)
  * [Setup you vagrant box environment](#setup-you-vagrant-box-environment)
  * [Test LDAP connection](#test-ldap-connection)
- [Part 2 : Setup workspace development with IntelliJ and Maven](#part-2---setup-workspace-development-with-intellij-and-maven)
  * [Install plugin from marketplace](#install-plugin-from-marketplace)
  * [Import Rudder project](#import-rudder-project)
  * [Setup Run configuration](#setup-run-configuration)
  * [Setup Module and sources](#setup-module-and-sources)
  * [Install maven dependencies](#install-maven-dependencies)
    + [XML Settings File](#xml-settings-file)
    + [Import dependencies](#import-dependencies)
  * [Setup File Watchers (Highly recommended)](#setup-file-watchers--highly-recommended-)
- [Now what ?](#now-what--)
    + [NCF api and Technique Editor](#ncf)
    + [Entire Script](#full-script)
    + [Documentations](#documentations)
    + [Contribution](#contribution)
    + [Bug reports](#bug-reports)
    + [Community](#community)

## Part 0 : Installation of required packages and softwares

### Needed packages
If you are starting with a clean machine, you might want to take it all, should be needed at some point
`apt update && apt install -y git openssh-server python3 python3-pip python python-pip openjdk-11-jdk net-tools ldap-utils maven`
`pip install docopt requests pexpect urllib3` (or `pip3`)

### VirtualBox installation
```bash
add-apt-repository "deb http://download.virtualbox.org/virtualbox/debian bionic contrib"
wget -qO- https://www.virtualbox.org/download/oracle_vbox_2016.asc | sudo apt-key add -
apt update
vboxlatest=$(wget -qO- https://download.virtualbox.org/virtualbox/LATEST.TXT)
vboxversion=$(echo $vboxlatest | cut -c1-3)
apt install -y virtualbox-${vboxversion}
wget https://download.virtualbox.org/virtualbox/${vboxlatest}/Oracle_VM_VirtualBox_Extension_Pack-${vboxlatest}.vbox-extpack
echo y | sudo vboxmanage extpack install --replace Oracle_VM_VirtualBox_Extension_Pack-${vboxlatest}.vbox-extpack
rm -rf Oracle_VM_VirtualBox_Extension_Pack-${vboxlatest}.vbox-extpack
```

### Vagrant installation
```bash
vagrantversion=$(wget -qO- https://raw.githubusercontent.com/hashicorp/vagrant/stable-website/version.txt) 
wget https://releases.hashicorp.com/vagrant/${vagrantversion}/vagrant_${vagrantversion}_x86_64.deb
apt install -y ./vagrant_${vagrantversion}_x86_64.deb
rm -rf vagrant_${vagrantversion}_x86_64.deb
```

### Intellij Idea Community installation
```bash
wget https://download.jetbrains.com/idea/ideaIC-2019.3.3.tar.gz
sudo tar -xzf ideaIC-2019.3.3.tar.gz -C /opt
rm -rf ideaIC-2019.3.3.tar.gz
```
> For later use: `/opt/idea-IC-193.6494.35/bin/idea.sh` to start intellij

### ldap apache directory studio installation
```bash
wget "http://apache.mirrors.ovh.net/ftp.apache.org/dist/directory/studio/2.0.0.v20180908-M14/ApacheDirectoryStudio-2.0.0.v20180908-M14-linux.gtk.x86_64.tar.gz"
sudo tar -xzf ApacheDirectoryStudio-2.0.0.v20180908-M14-linux.gtk.x86_64.tar.gz -C /opt
rm -rf ApacheDirectoryStudio-2.0.0.v20180908-M14-linux.gtk.x86_64.tar.gz
```
> For later use: `/opt/ApacheDirectoryStudio/ApacheDirectoryStudio` to start Apache Directory Studio

### Elm installation
```bash
wget -qO elm.gz https://github.com/elm/compiler/releases/download/0.19.0/binary-for-linux-64-bit.gz
gzip -d elm.gz
chmod +x elm
mv elm /usr/local/bin/
rm -rf elm.gz
```

### Rudder Test framework (RTF) installation
Please follow these [steps](https://github.com/Normation/rudder-tests#rudder-tests) to install Rudder Test framework

## Part 1 : Setup the local environment and the dev box

### Create a platform description file
`cd` into the directory of the previously installed RTF. We'll name it: `rudder-test` here.
`./platform/` contains examples of platform configurations.
Please read https://github.com/Normation/rudder-tests#adding-a-platform-or-an-os for further information.

Create a `<dev_env_name>.json` file in `./platform/` and put your platform's configuration in it.
Here is the most minimalistic example of a functional configuration:
```json
{
  "default":{ "run-with": "vagrant", "rudder-version": "6.0",
  "system": "debian9", "inventory-os": "debian" },
  "server": { "rudder-setup": "dev-server" }
}
```
Depending on your needs, a relay and some agents can be added:
```json
...
  "relay":  { "rudder-setup": "relay" },
  "agent1": { "rudder-setup": "agent", "system" : "debian8", "server": "relay" },
  "agent2": { "rudder-setup": "agent", "system" : "debian7" },
...
```
> Note: for testing purpose a local development environment is needed: this implies that the `server` field must at least contain `"rudder-setup": "dev-server"`.\
But if you only want to test Rudder without making any changes to the source code, replace `"rudder-setup": "dev-server"` by `"rudder-setup": "server"`

> Note: you will not be able to access Rudder at this stage, you will need to setup and run Rudder through IntelliJ to be able to access it locally.

When your file is ready, you will have to create and prepare the box by running :
```
./rtf platform setup <env's name>
```
> Important note: always do either:
- `./rtf platform setup <env's name>` from the `rudder-tests` folder, or
- `vagrant up <VM_id>`. The `<VM_id>` can be found by doing `vagrant global-status` as the user that created the VM in the first place

Now you can test that the environment is working by typing:
```
sudo netstat -laputn | grep 15432
```
The output should be:
```
tcp        0      0 0.0.0.0:15432           0.0.0.0:*               LISTEN
```
You can find the port number back in the Vagrantfile.

> For an eventual later use: this will not be necessary in this guide, but if you want to connect to the box, use (in `rudder-test` directory) :
```
vagrant ssh <env's name>_server
```

### Setup the local environnement
Meaning following steps are to be executed on your machine, not on the created VM
unless specified otherwise, the place repos are cloned is not important. Maybe keep them together in `~/rudder/<repos>`.

#### Create potentially missing files and add permission

1. Create some file for the webapp:
```
mkdir -p /var/rudder/inventories/incoming /var/rudder/share /var/rudder/inventories/accepted-nodes-updates /var/rudder/inventories/received /var/rudder/inventories/failed /var/log/rudder/core /var/log/rudder/compliance/ /var/rudder/run/       
touch /var/log/rudder/core/rudder-webapp.log /var/log/rudder/compliance/non-compliant-reports.log /var/rudder/run/api-token
```

2. Add permissions
```
sudo chown -R <username> /var/rudder/
sudo chown -R <username> /var/log/rudder
```

#### Synchronize techniques
1. Clone rudder-techniques repo by :
```
git clone https://github.com/Normation/rudder-techniques.git
```
2. Move technique's directory to `/var/rudder/configuration-repository/`
```
cp -r rudder-techniques/techniques /var/rudder/configuration-repository/
```

These steps get you the base techniques. To sync it:
3. git add and commit techniques directory in `/var/rudder/configuration-repository/`
```
git add techniques/
git commit -m "what ever you want"
```

### Setup you vagrant box environment
Meaning following steps are to be executed on the newly created VM machine. But first, you need to establish a connection (1.)

1. Connect to your vagrant box env, in `rudder-tests` directory :
```
vagrant ssh <env's name>_server
```

2. In vagrant box, find in `/opt/rudder/etc/openldap/slapd.conf` the two lines starting with `rootdn` and `rootpw` and keep them for the next section
rootdn should look like that :
`rootdn   "cn=Manager,cn=rudder-configuration"`

### Test LDAP connection
1. Run Apache Directory Studio (installed in Part 0)
2. in _LDAP -> New Connection_
- define a connection's name
- Hostname : localhost
- Port : 1389

Click on _Check Network Parameter_, a windows should appear and tell you that the connection was established successfully
if so click on next

![LDAP connection](LDAP_connection.png)

3. Complete with following parameters :
- Bind DN or user : `cn=Manager,cn=rudder-configuration` (`rootdn` parameter from previous step)
- Bind password : the `rootpw` value
click on Check the Authentication, it should be a success, click on apply and close if so
![LDAP Authentification](LDAP_auth.png)

Your LDAP connection is now working!

## Part 2 : Setup workspace development with IntelliJ and Maven
### Install plugin from marketplace
1. In IntelliJ install plugins : Scala, Elm, Jetty Runner and File watcher (recommended)

### Import Rudder project
1. Import your own forked rudder repository (for example):
```
git clone git@github.com:<gituser>/rudder.git
```

2. In IntelliJ : _Import Project or File -> New -> Module from existing sources_
and choose `/rudder/webapp/sources/rudder` or you can choose only the modules that you need (compliance will be faster)
Then select "Import Module from external model" and go for Maven
![Import all the Rudder's modules](select_modules.png)

### Setup Run configuration
1. In _Run -> Edit Configuration_ add a new configuration and choose Jetty Runner
- Module : <all modules>
- Path : `/rudder`
- WebApp Folder :
```
<rudder's path directory>/webapp/sources/rudder/rudder-web/src/main/webapp
```
- Classes Folder :
```
<rudder's path directory>/webapp/sources/rudder/rudder-web/target/classes
```
- Runs on Port : 8080
- VM Args :
```
\-DrjrDisableannotation=true -Drun.mode=production -XX:MaxPermSize=360m -Xms256m -Xmx2048m -XX:-UseLoopPredicate -XX:+UseG1GC -XX:+UnlockExperimentalVMOptions -XX:+UseStringDeduplication -Drudder.configFile=/home/<user>/rudder-tests/dev/configuration.properties
```
> Beware, replace `<user>`

If you are using JRebel add these following arguments :
```
-noverify -Drebel.lift_plugin=true -Drebel.spring_plugin=false -noverify -agentpath:<jrebel's path directory>/lib/libjrebel64.so
```

### Setup Module and sources
In IntelliJ : _File -> Project Structure -> Project settings_

- Make sure that the Project java SDK is at least at version 8, same for Project language level
![SDK Setting](SDK_setup.png)
- In `Modules` make sure that every your modules have a Language level at 8 at least
- For every modules mark src/main/Scala as Sources
![Example of project's structure sources setup](project_structure.png)
- Setup display format file manager
![Recommended options to display files in project tree](settings_filer.png)


### Install maven dependencies

#### XML Settings File
Use the template [here](settings.xml) (copy it from your local clone of the `rudder` repo) and create `~/.m2/settings.xml` and replace ` <localRepository>[PATH TO .m2 DIRECTORY]</localRepository>` line by the path to this directory.\
Do so manually or replace `<user>` by the actual user of the machine and use the following command:
```bash
cp rudder/contributing/settings.xml /home/<user>/.m2/settings.xml
sed -i "s/\[PATH TO \.m2 DIRECTORY\]/\/home\/<user>\//g" /home/<user>/.m2/settings.xml
```
> If **you are in the Rudder's Organization** put your `Username` and `Password` in the relative fields (\[CAPS\]) of the very same `settings.xml` file.

#### Import dependencies
To import dependencies that Rudder use you will have to run :
```
mvn clean install
```
Inside `rudder/webapp/sources/`.
> Note:  (rudder-code, rudder-web, rudder-rest, rudder-templates, rudder-templates-cli)

> Note: It can take several minutes to download all necessary dependencies.

### Setup File Watchers (Highly recommended)
The purpose of the File Watcher is to automatically apply actions on defined file types
when they are modified. In Rudder we use it to move generate frontend file (css, html, elm)
to the right directory when they are modify.
In Files -> Settings -> tools -> File Watchers
Add 3 new Watcher : for CSS, HTML and Elm file with these configurations (change `file type` according to the watcher) :

The `Program` field value is the same for these 3 watchers

![File watcher setting](file_watcher2.png)

Otherwise at every modification you will need to run the `src/main/elm/build-dev.sh` script in the module.

## Running Rudder

**Congratulation** the process is over !   
You can now compile and run Rudder in IntelliJ !   
You can access the application by running it from IntelliJ. The url is:
##### http://localhost:8080/rudder
> Warning : make sure your development's environment is running before running Rudder. `./rtf platform setup <env's name>` in rudder-test directory. Otherwise you will get errors in IntelliJ's console.

Let's code ! :rocket:

## Now what ?
#### NCF api and Technique Editor
The webapp neither comes with the NCF api nor the Technique Editor.
If your testing case requires it to be running, please follow this documentation link ([ncf dev setup](https://github.com/Normation/ncf/tree/master/api#development))
> Note: the setup process might not be painless. Some tweaks could be necessary depending on your distribution

#### Entire Script
All the previous steps BUT the ncf-api/technique editor are summed up in the following script: [setup_dev_env.sh](https://github.com/Normation/rudder/blob/master/contributing/setup_dev_env.sh).\
Though, another automated setup script is available directly in the ncf repository (see previous section).

Do not run this script as sudo! Though some commands require sudo privileges, you may be prompted to type sudo password

> Important: as a requirement, if not done yet you need to fork the following repo: `https://github.com/Normation/rudder`.

The first param is your local user 
The second is your gitusername (used to clone your `Normation/rudder` fork)
This script should be copied anywhere on your machine and simply executed.
```bash
./setup_dev_env.sh <user> <gituser>
```

> Important note: This script does not setup Intellij ([Setup workspace development with IntelliJ and Maven](#part-2---setup-workspace-development-with-intellij-and-maven)) 
 and Apache Directory Studio ([LDAP connection](#test-ldap-connection)). These still have to be set manually.

> To start intellij, run: `/opt/idea-IC-193.6494.35/bin/idea.sh`

> To start Apache Directory Studio: `/opt/ApacheDirectoryStudio/ApacheDirectoryStudio`

> Important note: this script should only be ran once. Every other startup, only do the following command: `./rtf platform setup debian9_dev`

> All rudder repos will be cloned (including rtf and ncf) in `/home/<user>/rudder/`

> Disclaimer: this script might not work on your machine. If it does not, it still is a good guideline when trying to setup a dev environment since it traces every single step of this readme + rtf setup readme


#### Documentations
If you want to learn how to use Rudder and its web interface, consult the documentation here : https://docs.rudder.io/reference/5.0/usage/web_interface.html :shipit:

#### Contribution
If you want to submit your code, please feel to contribute by following the [code submit process](https://github.com/Normation/rudder/blob/master/CONTRIBUTING.adoc), we would be happy to review your code and and see new contributors join the boat! :heart:

#### Bug reports
If you detect any bugs in the application please feel free to report it by signing up here if you don't have already an account: https://issues.rudder.io/ :bug:

#### Community
If you want to discuss about Rudder and get some helps, you can join our Gitter : https://gitter.im/normation/rudder :speech_balloon:
