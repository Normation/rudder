import os, logging, re, json, textwrap
from distutils.version import LooseVersion, StrictVersion
import rudderPkgUtils as utils

# Compare versions with the form "w.x-y.z"
class WebappVersion:
    def __init__(self, version):
        match = re.search(
            r'(?P<wx>[0-9]+.[0-9]+(.[0-9]+)?)(?P<mode>~[a-z0-9]+)?(-[0-9]+.[0-9]+.*)?',
            version,
        )
        if match.group('mode') is not None:
            self.rudder_mode = (
                match.group('mode').replace('~alpha', 'a').replace('~beta', 'b').replace('~rc', 'c')
            )
        else:
            self.rudder_mode = ''

        self.rudder_version = match.group('wx')
        self.version = LooseVersion(self.rudder_version + self.rudder_mode)

    def __eq__(self, other):
        if isinstance(other, WebappVersion):
            return self.get_xyz() == other.get_xyz() and self.get_mode() == other.get_mode()
        return False

    def __lt__(self, other):
        if isinstance(other, WebappVersion):
            if LooseVersion(self.get_xyz()) < LooseVersion(other.get_xyz()):
                return True
            elif LooseVersion(self.get_xyz()) == LooseVersion(other.get_xyz()):
                if self.get_mode() == '':
                    return False
                elif other.get_mode() == '':
                    return True
                else:
                    return self.version < other.version
        return False

    def __le__(self, other):
        return True if self.__eq__(other) else self.__lt__(other)

    def __ne__(self, other):
        return not self.__eq__(other)

    def __gt__(self, other):
        return not self.__le__(other)

    def __ge__(self, other):
        return not self.__lt__(other)

    def get_xyz(self):
        return self.rudder_version

    def get_mode(self):
        return self.rudder_mode


"""
    Versions can contains the words "-SNAPSHOT" at the end, this should not be a problem at the moment since it is only present in nightly.
    Moreover, we should not have to compare a nightly version to a release one.
"""


class PluginVersion:
    def __init__(self, pluginLongVersion):
        match = re.search(
            r'(?P<rudderVersion>(?P<rudderMajor>[0-9]+\.[0-9]+)(\.[0-9]+(~(alpha|beta|rc)[0-9]+)?)?)-(?P<pluginShortVersion>[0-9]+\.[0-9]+)(-(?P<mode>[a-zA-Z]+))?',
            pluginLongVersion,
        )
        if match.group('mode') is None:
            self.mode = 'release'
        elif match.group('mode') in ['SNAPSHOT', 'nightly']:
            self.mode = 'nightly'
        else:
            utils.fail(
                'The version %s does not respect the version syntax. Unknown mode found: %s'
                % (pluginLongVersion, match.group('mode'))
            )

        if match.group('rudderVersion') is None or match.group('pluginShortVersion') is None:
            utils.fail(
                'The version %s does not respect the version syntax [0-9]+.[0-9]+.[0-9]+-[0-9]+.[0-9]+(-SNAPSHOT)?'
                % (pluginLongVersion)
            )
        else:
            self.rudderVersion = match.group('rudderVersion')
            self.rudderMajor = match.group('rudderMajor')
            self.pluginShortVersion = match.group('pluginShortVersion')
            self.pluginLongVersion = pluginLongVersion
            self.versionToCompare = WebappVersion(self.rudderVersion)

    def __hash__(self):
        return hash((self.mode, self.rudderVersion, self.pluginShortVersion))

    def __eq__(self, other):
        if isinstance(other, PluginVersion):
            return (
                self.mode == other.mode
                and self.rudderVersion == other.rudderVersion
                and self.pluginShortVersion == other.pluginShortVersion
                and self.versionToCompare == other.versionToCompare
            )
        return False

    # nightly are inferior to their release equivalent
    # Compare:
    #  - WebappVersion
    #  - then Plugin version
    #  - then Plugin mode
    def __lt__(self, other):
        if self.__eq__(other) == True:
            return False
        selfShortVersion = StrictVersion(self.pluginShortVersion)
        otherShortVersion = StrictVersion(other.pluginShortVersion)
        if self.versionToCompare < other.versionToCompare:
            return True
        if self.versionToCompare == other.versionToCompare:
            if selfShortVersion < otherShortVersion:
                return True
            elif selfShortVersion == otherShortVersion:
                return self.mode == 'nightly'
        return False

    def __le__(self, other):
        if self.__eq__(other) == True:
            return True
        else:
            return self.__lt__(other)

    def __ne__(self, other):
        return not self.__eq__(other)

    def __gt__(self, other):
        return not self.__le__(other)

    def __ge__(self, other):
        return not self.__lt__(other)


"""
    Define an object based on a .rpkg file.
"""


class Rpkg:
    def __init__(self, longName, shortName, path, version, metadata):
        self.longName = longName
        self.shortName = shortName
        self.path = path
        self.version = version
        self.metadata = metadata

    def getMode(self):
        return self.version.mode

    def isCompatible(self, exact_version):
        return utils.check_plugin_compatibility(self.metadata, exact_version)

    def show_metadata(self):
        # Mandatory
        print('Name: ' + self.metadata['name'])
        print('Short name: ' + self.metadata['name'].replace('rudder-plugin-', ''))
        print('Version: ' + self.metadata['version'])

        # Description
        description = ''
        if 'description' in self.metadata:
            description = self.metadata['description']
        print('Description:')
        for line in textwrap.wrap(description, 80):
            print('  ' + line)

        # Build info
        print('Build-date: ' + self.metadata['build-date'])
        print('Build-commit: ' + self.metadata['build-commit'])

        # Dependencies info
        if 'depends' in self.metadata:
            for dependType in self.metadata['depends'].keys():
                prefix = 'Depends %s: ' % (dependType)
                suffix = ', '.join(str(x) for x in self.metadata['depends'][dependType])
                print(prefix + suffix)

        # Jar info
        jar = ''
        if 'jar-file' in self.metadata:
            jar = ', '.join(str(x) for x in self.metadata['jar-files'])
        print('Jar files: ' + jar)

        # Content info
        print('Content:')
        for iContent in self.metadata['content'].keys():
            print('  %s: %s' % (iContent, self.metadata['content'][iContent]))

    def __eq__(self, other):
        if isinstance(other, Rpkg):
            return self.longName == other.longName and self.version == other.version
        return False

    def __lt__(self, other):
        return self.version < other.version

    def __le__(self, other):
        return self.version <= other.version

    def __ne__(self, other):
        return not self.__eq__(other)

    def __gt__(self, other):
        return self.version > other.version

    def __ge__(self, other):
        return self.version >= other.version

    def __hash__(self):
        return hash((self.longName, self.version))

    def __str__(self):
        return self.path

    def __repr__(self):
        return self.path

    def show(self):
        print(self.path + ':')
        print(json.dumps(self.metadata, indent=4, sort_keys=True))

    def toTabulate(self):
        compatibility = 'No'

        if self.isCompatible(True):
            compatibility = 'Yes'
        if self.isCompatible(False):
            compatibility = (
                'Not build for exact Rudder version, May be ok, but it can crash, please upgrade'
            )
        return [
            self.longName,
            self.version.mode,
            self.version.pluginLongVersion,
            compatibility,
        ]
