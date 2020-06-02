"""
    Contains functions called by the parser and nothing else.
"""
import sys
import os
import io
import re
import shutil
import requests as requests
import logging
import plugin
import rpkg
import rudderPkgUtils as utils
from lxml import html
import traceback


"""
    Expect a list of path as parameter.
    Try to install the given rpkgs.
"""
def install_file(package_files):
    for package_file in package_files:
        logging.info("Installing " + package_file)
        # First, check if file exists
        if not os.path.isfile(package_file):
            utils.fail("Error: Package file " + package_file + " does not exist")
        metadata = utils.rpkg_metadata(package_file)
        exist = utils.package_check(metadata)
        # As dependencies are only displayed messages for now,
        # wait until the end to make them visible.
        # This should be moved before actual installation once implemented.
        if not utils.install_dependencies(metadata):
            exit(1)
        if exist:
            logging.info("The package is already installed, I will upgrade it.")
        script_dir = utils.extract_scripts(metadata, package_file)
        utils.run_script("preinst", script_dir, exist)
        utils.install(metadata, package_file, exist)
        utils.run_script("postinst", script_dir, exist)
        if metadata['type'] == 'plugin' and 'jar-files' in metadata:
            for j in metadata['jar-files']:
                utils.jar_status(j, True)

"""
    List installed plugins.
"""
def package_list_installed():
    toPrint = []
    printLatest = os.path.isfile(utils.INDEX_PATH)

    pluginName = []
    version = []
    latestRelease = []
    currentStatus = []

    for p in utils.DB["plugins"].keys():
        pluginName.append(p)

        # Get version
        currentVersion = rpkg.PluginVersion(utils.DB["plugins"][p]["version"])
        version.append(currentVersion.pluginLongVersion)

        # Get status
        # Only plugin containing jars can be disabled
        status = "enabled"
        if not os.path.exists(utils.PLUGINS_CONTEXT_XML):
            return
        text = open(utils.PLUGINS_CONTEXT_XML).read()
        match = re.search(r'<Set name="extraClasspath">(.*?)</Set>', text)
        if match:
            enabled = match.group(1).split(',')
        metadata = utils.DB["plugins"][p]
        if 'jar-files' in metadata:
            for j in metadata['jar-files']:
                if j not in enabled:
                   status = "disabled"
        currentStatus.append(status)

        # Get latest available version
        extra = ""
        try:
            if printLatest:
                pkgs = plugin.Plugin(p)
                pkgs.getAvailablePackages()
                latestVersion = pkgs.getLatestCompatibleRelease().version
                if currentVersion < latestVersion:
                    extra = "version %s is available"%(latestVersion.pluginLongVersion)
                latestRelease.append(latestVersion.pluginLongVersion + " " + extra)
            else:
                latestRelease.append("")
        except:
            latestRelease.append("")

    table = [
                { "title": "Plugin Name", "value": pluginName },
                { "title": "Version"    , "value": version    },
                { "title": "Status"     , "value": currentStatus  },
            ]
    if printLatest:
        table.insert(2, { "title": "Latest release", "value": latestRelease })
    print(utils.dictToAsciiTable(table))

"""
    List available plugin names.
"""
def package_list_name():
    utils.readConf()
    pluginDict = utils.list_plugin_name()
    pluginName = []
    shortName = []
    latestRelease = []
    description = []
    for p in pluginDict.keys():
        if utils.check_download(utils.URL + "/" + utils.RUDDER_VERSION + "/" + str(pluginDict[p][0])):
            pluginName.append(str(p))
            shortName.append(str(pluginDict[p][0]))
            description.append(str(pluginDict[p][1]))

            pkgs = plugin.Plugin(p)
            pkgs.getAvailablePackages()
            try:
                latestVersion = pkgs.getLatestCompatibleRelease().version.pluginLongVersion
            except:
                latestVersion = ""
            latestRelease.append(latestVersion)
    table = [
                { "title": "Plugin Name"      , "value": pluginName    },
                { "title": "Plugin Short Name", "value": shortName     },
                { "title": "Description"      , "value": description   },
                { "title": "Latest release"   , "value": latestRelease }
            ]
    print(utils.dictToAsciiTable(table))

"""
    Given a name, a version, and a mode, print associated plugin metadata.
    If no version is given it will take the latest version in the given mode.
"""
def package_show(name, version, mode):
    utils.readConf()
    pkgs = plugin.Plugin(name[0])
    pkgs.getAvailablePackages()
    if version != "":
        rpkg = pkgs.getRpkgByLongVersion(version, mode)
    else:
        if mode == "release":
            rpkg = pkgs.getLatestCompatibleRelease()
        else:
            rpkg = pkgs.getLatestCompatibleNightly()
    if rpkg is not None:
        rpkg.show_metadata()
    else:
        utils.fail("Could not find any package in %s for %s"%(mode, name))


"""
    Given a name, lookf for a the given packages availables for this plugin.
"""
def package_search(name):
    utils.readConf()
    pkgs = plugin.Plugin(name[0])
    pkgs.getAvailablePackages()
    pluginName = []
    releaseMode = []
    version = []
    compatible = []

    for iRpkg in sorted(pkgs.packagesInfo):
        data = iRpkg.toTabulate()
        pluginName.append(data[0])
        releaseMode.append(data[1])
        version.append(data[2])
        compatible.append(data[3])

    table = [
                { "title": "Plugin Name" , "value": pluginName  },
                { "title": "Release Mode", "value": releaseMode },
                { "title": "Version"     , "value": version     },
                { "title": "Compatible"  , "value": compatible  },
            ]
    print(utils.dictToAsciiTable(table))

"""
    Install the package for a given plugin in a specific version.
    It will not check for compatibility and will let it to the installer since
    the user explicitly asked for this version.
"""
def package_install_specific_version(name, longVersion, mode="release"):
    utils.readConf()
    pkgs = plugin.Plugin(name[0])
    pkgs.getAvailablePackages()
    rpkg = pkgs.getRpkgByLongVersion(longVersion, mode)
    if rpkg is not None:
        rpkgPath = utils.downloadByRpkg(rpkg)
        install_file([rpkgPath])
    else:
        utils.fail("Could not find any package for %s in version %s"%(name, longVersion))

"""
    Install the latest available and compatible package for a given plugin.
    If no release mode is given, it will only look in the released rpkg.
"""
def package_install_latest(name, mode="release"):
    utils.readConf()
    pkgs = plugin.Plugin(name[0])
    pkgs.getAvailablePackages()
    if mode == "release":
        rpkg = pkgs.getLatestCompatibleRelease()
    else:
        rpkg = pkgs.getLatestCompatibleNightly()
    if rpkg is not None:
        rpkgPath = utils.downloadByRpkg(rpkg)
        install_file([rpkgPath])
    else:
        utils.fail("Could not find any compatible %s for %s"%(mode, name))

"""Remove a given plugin. Expect a list of name as parameter."""
def remove(package_names):
    for package_name in package_names:
        logging.info("Removing " + package_name)
        if package_name not in utils.DB["plugins"]:
            utils.fail("This package is not installed. Aborting!", 2)
        script_dir = utils.DB_DIRECTORY + "/" + package_name
        metadata = utils.DB["plugins"][package_name]
        if metadata['type'] == 'plugin' and 'jar-files' in metadata:
            for j in metadata['jar-files']:
                utils.jar_status(j, False)
        utils.run_script("prerm", script_dir, None)
        utils.remove_files(metadata)
        utils.run_script("postrm", script_dir, None)
        shutil.rmtree(script_dir)
        del utils.DB["plugins"][package_name]
        utils.db_save()

def rudder_postupgrade():
    for plugin in utils.DB["plugins"]:
        script_dir = utils.DB_DIRECTORY + "/" + plugin
        utils.run_script("postinst", script_dir, True)

def check_compatibility():
    for p in utils.DB["plugins"]:
        metadata = utils.DB["plugins"][p]
        if not utils.check_plugin_compatibility(metadata):
            logging.warning("Plugin " + p + " is not compatible with rudder anymore, disabling it.")
            if 'jar-files' in metadata:
                for j in metadata['jar-files']:
                    utils.jar_status(j, False)
            logging.warning("Please install a new version of " + p + " to enable it again.")
            logging.info("")
            utils.jetty_needs_restart = True

def plugin_save_status():
    enabled = []
    if not os.path.exists(utils.PLUGINS_CONTEXT_XML):
        return
    text = open(utils.PLUGINS_CONTEXT_XML).read()
    match = re.search(r'<Set name="extraClasspath">(.*?)</Set>', text)
    if match:
        enabled = match.group(1).split(',')
    for p in utils.DB["plugins"]:
        metadata = utils.DB["plugins"][p]
        if 'jar-files' in metadata:
            for j in metadata['jar-files']:
                if j in enabled:
                    print("enabled " + j)
                else:
                    print("disabled " + j)

def plugin_restore_status():
    lines = sys.stdin.readlines()
    for line in lines:
        line = line.strip()
        if line.startswith("enabled "):
            print("enable " + line.split(' ')[1])
            utils.jar_status(line.split(' ')[1], True)
        if line.startswith("disabled "):
            utils.jar_status(line.split(' ')[1], False)
    check_compatibility()

def plugin_status(plugins, status):
    for plugin in plugins:
        if status:
            print("Enabling " + plugin)
        else:
            print("Disabling " + plugin)
        if plugin not in utils.DB["plugins"]:
            utils.fail("Unknown plugin " + plugin)
        metadata = utils.DB["plugins"][plugin]
        if 'jar-files' in metadata:
            for j in metadata['jar-files']:
                utils.jar_status(j, status)

def plugin_disable_all():
    plugin_status(utils.DB["plugins"].keys(), False)

def plugin_enable_all():
    plugin_status(utils.DB["plugins"].keys(), True)

"""
Update the licences from the Rudder repo
It first check for the */licenses page and find the subfolders.
Iterate through them to find all *.license files and *.key files.
"""
def update_licenses():
    utils.readConf()
    url = utils.URL + "/licenses"
    r = requests.get(url, auth=(utils.USERNAME, utils.PASSWORD))
    htmlElements = html.document_fromstring(r.text)
    htmlElements.make_links_absolute(url + "/", resolve_base_href=True)

    folderPattern = re.compile(url + '/[a-z0-9\-]+/')
    downloadPattern = re.compile('\S+\.(license|key)')

    # Filter to only keep folders
    licenseFolders = list(filter(lambda x: folderPattern.match(x), [elem[2] for elem in htmlElements.iterlinks()]))

    # Find the .licence and .key files under each folder
    for folderUrl in set(licenseFolders):
        r = requests.get(folderUrl, auth=(utils.USERNAME, utils.PASSWORD))
        htmlElements = html.document_fromstring(r.text)
        htmlElements.make_links_absolute(folderUrl + "/", resolve_base_href=True)
        for link in set([elem[2] for elem in htmlElements.iterlinks()]):
            match = downloadPattern.search(link)
            if match is not None:
                logging.info("downloading %s"%(link))
                utils.download(link, utils.LICENCES_PATH + "/" + os.path.basename(link))

# TODO validate index sign if any?
""" Download the index file on the repos """
def update():
    utils.readConf()
    logging.debug('Updating the index')
    utils.getRudderKey()
    # backup the current indexFile if it exists
    logging.debug("backuping %s in %s"%(utils.INDEX_PATH, utils.INDEX_PATH + ".bkp"))
    if os.path.isfile(utils.INDEX_PATH):
        os.rename(utils.INDEX_PATH, utils.INDEX_PATH + ".bkp")
    try:
        utils.download(utils.URL + "/" + "rpkg.index")
    except Exception as e:
        traceback.print_exc(file=sys.stdout)
        if os.path.isfile(utils.INDEX_PATH + ".bkp"):
            logging.debug("restoring %s from %s"%(utils.INDEX_PATH, utils.INDEX_PATH + ".bkp"))
            os.rename(utils.INDEX_PATH + ".bkp", utils.INDEX_PATH)
        utils.fail(e)

"""
    Upgrade all plugins install in their latest compatible version
"""
def upgrade_all(mode):
    for p in utils.DB["plugins"].keys():
        currentVersion = rpkg.PluginVersion(utils.DB["plugins"][p]["version"])
        pkgs = plugin.Plugin(p)
        pkgs.getAvailablePackages()
        if mode == "nightly":
            latestVersion = pkgs.getLatestCompatibleNightly().version
        else:
            latestVersion = pkgs.getLatestCompatibleRelease().version
        if currentVersion < latestVersion:
            print("The plugin %s is installed in version %s. The version %s %s is available, the plugin will be upgraded."%(p, currentVersion.pluginLongVersion, mode, latestVersion.pluginLongVersion))
            package_install_latest([p], mode)
        else:
            print("No newer %s compatible versions found for the plugin %s"%(mode, p))
