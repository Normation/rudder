import json, logging, re
import rudderPkgUtils as utils
import rpkg

"""
    Define a Plugin object, which is a group of all the rpkgs corresponding to the plugin.
    Since multiple rpkgs can provide the exact same plugin, we are using sets. For each multiple occurence,
    only the last rpkg file checked will be kept.
"""
class Plugin:
    def __init__(self, name):
        if re.match(r'rudder-plugin-[0-9a-zA-Z]+', name):
            name = name.replace("rudder-plugin-", "")
        self.name = name
        self.packagesInfo = set()
        self.releasePackagesInfo = set()
        self.nightlyPackagesInfo = set()

    """Parse the index file of the repo and fulfill the sets with rpkgs matching the plugin name."""
    def getAvailablePackages(self):
        try:
            with open(utils.INDEX_PATH) as f:
                data = json.load(f)
            for metadata in data:
                if metadata['name'] == "rudder-plugin-%s"%(self.name):
                    version = rpkg.PluginVersion(metadata['version'])
                    package = rpkg.Rpkg(metadata['name'], self.name, metadata['path'], version, metadata)
                    self.packagesInfo.add(package)
                    if version.mode == 'release':
                        self.releasePackagesInfo.add(package)
                    elif version.mode == 'nightly':
                        self.nightlyPackagesInfo.add(package)
                    else:
                        utils.fail('Unknown release mode, found %s and expecting release or nightly'%(version.mode))
        except Exception as e:
            logging.error("could not parse the index file %s"%(utils.INDEX_PATH))
            utils.fail("could not parse the index file %s"%(utils.INDEX_PATH))
        if not len(self.packagesInfo):
            logging.debug('No packages were found corresponding to %s'%(self.name))
        else:
            logging.debug("Found corresponding packages: %s"%(self.packagesInfo))

    """Return a set of Rpkg objects, matching all the rpkgs found compatible with the current Rudder version."""
    def getCompatiblePackagesInfo(self):
        compatibles = set()
        for iRpkg in self.packagesInfo:
            if iRpkg.isCompatible():
                compatibles.add(iRpkg)
        return compatibles

    """Return a set of Rpkg objects, matching all the nightly rpkgs found compatible with the current Rudder version."""
    def getCompatibleNightly(self):
        compatibles = set()
        for iRpkg in self.nightlyPackagesInfo:
            if iRpkg.isCompatible():
                compatibles.add(iRpkg)
        return compatibles

    """Return a set of Rpkg objects, matching all the released rpkgs found compatible with the current Rudder version."""
    def getCompatibleRelease(self):
        compatibles = set()
        for iRpkg in self.releasePackagesInfo:
            if iRpkg.isCompatible():
                compatibles.add(iRpkg)
        return compatibles


    """From a given version and release mode, return the corresponding Rpkg object."""
    def getRpkgByLongVersion(self, longVersion, mode):
        for iRpkg in self.packagesInfo :
            if (iRpkg.version.pluginLongVersion == longVersion and iRpkg.version.mode == mode):
                return iRpkg
        return None

    """Return the latest released Rpkg object found compatible with current Rudder version."""
    def getLatestCompatibleRelease(self):
        try:
            return max(self.getCompatibleRelease())
        except:
            return None

    """Return the latest nightly Rpkg object found compatible with current Rudder version."""
    def getLatestCompatibleNightly(self):
        try:
            return max(self.getCompatibleNightly())
        except:
            return None
