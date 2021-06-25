import os, logging, sys, re, hashlib, requests, json
import distutils.spawn
import requests.auth
from pprint import pprint
from pkg_resources import parse_version
import fcntl, termios, struct, traceback
from distutils.version import StrictVersion
import subprocess

try:
    import ConfigParser as configparser
except Exception:
    import configparser

DEVNULL = open(os.devnull, 'wb')
logger = logging.getLogger('rudder-pkg')

try:
    import rpm
except:
    logger.debug('No rpm python lib found')

try:
    import apt
except:
    logger.debug('No apt python lib found')


# See global variables at the end of the file


def makedirs(path):
    if not os.path.isdir(path):
        os.makedirs(path)


def run(
    cmd, input=None, stdout=DEVNULL, stderr=DEVNULL, capture_output=False, shell=False, check=False
):
    """
    Local version of the run method define in the python 3.5+ versions of the subprocess module.
    It returns a tuple (exit code, stdout, stderr) instead of a CompletedProcess object.

    The catch part is for compatibility with older python versions (ie 2.7) that do not support
    the subprocess.run function. We should be able to remove it when we will drop the support
    for centos7 for the rudder-server-root package
    """
    logger.debug('Execute: %s' % cmd)
    if capture_output:
        stdout = subprocess.PIPE
        stderr = subprocess.PIPE
    try:
        process = subprocess.run(
            args=cmd, input=input, stdout=stdout, stderr=stderr, shell=shell, check=check
        )
        return (process.returncode, process.stdout, process.stderr)
    except AttributeError as e:
        if input is not None:
            stdin = subprocess.PIPE
        else:
            stdin = None
        process = subprocess.Popen(args=cmd, stdin=stdin, stdout=stdout, stderr=stderr, shell=shell)
        output, error = process.communicate(input=input)
        retcode = process.poll()
        if check and retcode != 0:
            logger.error("execution of '%s' failed" % cmd)
            logger.error(error)
            fail(output, retcode)
        return (retcode, output, error)
    except:
        fail('Could not execute %s' % cmd, 1)


''' Get Terminal width '''


def terminal_size():
    try:
        if sys.stdout.isatty():
            h, w, hp, wp = struct.unpack(
                'HHHH', fcntl.ioctl(0, termios.TIOCGWINSZ, struct.pack('HHHH', 0, 0, 0, 0))
            )
        return h, w
    except:
        pass
    return 25, 80


def fail(message, code=1, exit_on_error=True):
    logger.debug(traceback.format_exc())
    logger.error(message)
    if exit_on_error:
        exit(code)


def sha512(fname):
    hash_sha512 = hashlib.sha512()
    with open(fname, 'rb') as f:
        for chunk in iter(lambda: f.read(4096), b''):
            hash_sha512.update(chunk)
    return hash_sha512.hexdigest()


'''
   Print dict list in a fancy manner
   Assume the list is following the format:
   [
     { "title": "strkey1", "value" : [ "str", "str2", ... ] },
     { "tilte": "strkey2", "value" : [ "str", "str2", ... ] },
     ...
   ]
'''


def dictToAsciiTable(data):
    # Get maximum text length to print
    lengths = [len(max([column['title']] + column['value'], key=len)) for column in data]
    lenstr = '| ' + ' | '.join('{:<%s}' % m for m in lengths) + ' |'
    lenstr += '\n'

    # Define sep bar for header
    sepBar = ''
    for iSep in lengths:
        sepBar += '+' + '-' * (int(iSep) + 2)
    sepBar += '+\n'

    outmsg = sepBar + lenstr.format(*[column['title'] for column in data]) + sepBar

    # Write rows, at least an empty one if everything is empty
    printedRows = 0
    maxRows = max([len(column['value']) + 1 for column in data])
    while True:
        row = []
        for column in data:
            if len(column['value']) > printedRows:
                row.append(column['value'][printedRows])
            else:
                row.append('')
        outmsg += lenstr.format(*row)
        printedRows = printedRows + 1
        if printedRows >= maxRows - 1:
            break
    return outmsg


# Use an authenticated proxy to access download.rudder.io, requests can only do basic auth, but we are not doing it here nonetheless
# Got the code from a SO answer: https://stackoverflow.com/a/13520486/1939653
class HTTPProxyDigestAuth(requests.auth.HTTPDigestAuth):
    def handle_407(self, r):
        """Takes the given response and tries digest-auth, if needed."""
        num_407_calls = r.request.hooks['response'].count(self.handle_407)
        s_auth = r.headers.get('Proxy-authenticate', '')
        if 'digest' in s_auth.lower() and num_407_calls < 2:
            self.chal = requests.auth.parse_dict_header(s_auth.replace('Digest ', ''))
            # Consume content and release the original connection
            # to allow our new request to reuse the same one.
            r.content
            r.raw.release_conn()
            r.request.headers['Authorization'] = self.build_digest_header(
                r.request.method, r.request.url
            )
            r.request.send(anyway=True)
            _r = r.request.response
            _r.history.append(r)
            return _r
        return r

    def __call__(self, r):
        if self.last_nonce:
            r.headers['Proxy-Authorization'] = self.build_digest_header(r.method, r.url)
        r.register_hook('response', self.handle_407)
        return r


def getRequest(url, stream, timeout=60):
    if PROXY_URL == '':
        return requests.get(url, auth=(USERNAME, PASSWORD), stream=stream, timeout=timeout)
    else:
        proxies = {'https': PROXY_URL, 'http': PROXY_URL}
        if PROXY_USERNAME != '' and PROXY_PASSWORD != '':
            auth = HTTPProxyDigestAuth(PROXY_USERNAME, PROXY_PASSWORD)
            return requests.get(url, proxies=proxies, auth=auth, stream=stream, timeout=timeout)
        else:
            return requests.get(
                url, auth=(USERNAME, PASSWORD), proxies=proxies, stream=stream, timeout=timeout
            )


# Check if plugin URL defined in configuration file can be accessed, so we can fail early our cmmands
def check_url():
    try:
        r = getRequest(URL, False, 5)
        if r.status_code == 401:
            fail(
                'Received a HTTP 401 Unauthorized error when trying to get %s. Please check your credentials in %s'
                % (URL, CONFIG_PATH)
            )
        elif r.status_code > 400:
            fail('Received a HTTP %s error when trying to get %s' % (r.status_code, URL))
        else:
            True
    except Exception as e:
        fail('An error occurred while checking access to %s:\n%s' % (URL, e))


'''
   From a complete url, try to download a file. The destination path will be determined by the complete url
   after removing the prefix designing the repo url defined in the conf file.
   Ex: completeUrl = http://download.rudder.io/plugins/./5.0/windows/release/SHA512SUMS
           repoUrl = http://download.rudder.io/plugins
        => fileDst = /tmp/rpkg/./5.0/windows/release/SHA512SUMS
'''


def download(completeUrl, dst=''):
    if dst == '':
        fileDst = FOLDER_PATH + '/' + completeUrl.replace(URL + '/', '')
    else:
        fileDst = dst
    fileDir = os.path.dirname(fileDst)
    makedirs(fileDir)
    try:
        r = getRequest(completeUrl, True)
        with open(fileDst, 'wb') as f:
            bar_length = int(r.headers.get('content-length'))
            if r.status_code == 200:
                if bar_length is None:   # no content length header
                    f.write(r.content)
                else:
                    bar_length = int(bar_length)
                    for data in r.iter_content(chunk_size=4096):
                        f.write(data)
            elif r.status_code == 401:
                fail(
                    'Received a HTTP 401 Unauthorized error when trying to get %s. Please check your credentials in %s'
                    % (completeUrl, CONFIG_PATH)
                )
            elif r.status_code > 400:
                fail(
                    'Received a HTTP %s error when trying to get %s' % (r.status_code, completeUrl)
                )
        return fileDst
    except Exception as e:
        fail('An error happened while downloading from %s:\n%s' % (completeUrl, e))


'''
    Make a HEAD request on the given url, return true if result is 200, false instead
'''


def check_download(completeUrl):
    if PROXY_URL == '':
        r = requests.head(completeUrl, auth=(USERNAME, PASSWORD))
    else:
        proxies = {'https': PROXY_URL}
        if PROXY_USERNAME != '' and PROXY_PASSWORD != '':
            auth = HTTPProxyDigestAuth(PROXY_USERNAME, PROXY_PASSWORD)
            r = requests.head(completeUrl, proxies=proxies, auth=auth)
        else:
            r = requests.head(completeUrl, auth=(USERNAME, PASSWORD), proxies=proxies)

    return r.status_code <= 301


'''
   Verify Hash
'''


def verifyHash(targetPath, shaSumPath):
    fileHash = []
    (folder, leaf) = os.path.split(targetPath)
    lines = [line.rstrip('\n') for line in open(shaSumPath)]
    pattern = re.compile(r'(?P<hash>[a-zA-Z0-9]+)[\s]+%s' % (leaf))
    logger.info('verifying file hash')
    for line in lines:
        match = pattern.search(line)
        if match:
            fileHash.append(match.group('hash'))
    if len(fileHash) != 1:
        logger.warning('Multiple hash found matching the package, this should not happen')
    if sha512(targetPath) in fileHash:
        logger.info('=> OK!\n')
        return True
    fail('hash could not be verified')


'''
   From a complete url, try to download a file. The destination path will be determined by the complete url
   after removing the prefix designing the repo url defined in the conf file.
   Ex: completeUrl = http://download.rudder.io/plugins/./5.0/windows/release/SHA512SUMS
           repoUrl = http://download.rudder.io/plugins
        => fileDst = /tmp/rpkg/./5.0/windows/release/SHA512SUMS

   If the verification or the download fails, it will exit with an error, otherwise, return the path
   of the local rpkg path verified and downloaded.
'''


def download_and_verify(completeUrl, dst=''):
    global GPG_HOME
    # download the target file
    logger.info('downloading rpkg file  %s' % (completeUrl))
    targetPath = download(completeUrl, dst)
    # download the attached SHASUM and SHASUM.asc
    (baseUrl, leaf) = os.path.split(completeUrl)
    logger.info('downloading shasum file  %s' % (baseUrl + '/SHA512SUMS'))
    shaSumPath = download(baseUrl + '/SHA512SUMS', dst)
    logger.info('downloading shasum sign file  %s' % (baseUrl + '/SHA512SUMS.asc'))
    signPath = download(baseUrl + '/SHA512SUMS.asc', dst)
    # verify authenticity
    gpgCommand = ['/usr/bin/gpg', '--homedir', GPG_HOME, '--verify', '--', signPath, shaSumPath]
    logger.debug('Executing %s' % (gpgCommand))
    logger.info('verifying shasum file signature %s' % (gpgCommand))
    run(gpgCommand, check=True)
    logger.info('=> OK!\n')
    # verify hash
    if verifyHash(targetPath, shaSumPath):
        return targetPath
    fail('Hash verification of %s failed' % (targetPath))


'''Download the .rpkg file matching the given rpkg Object and verify its authenticity'''


def downloadByRpkg(rpkg):
    return download_and_verify(URL + '/' + rpkg.path)


def package_check(metadata, version):
    if 'type' not in metadata or metadata['type'] != 'plugin':
        fail('Package type not supported')
    # sanity checks
    if 'name' not in metadata:
        fail('Package name undefined')
    name = metadata['name']
    if 'version' not in metadata:
        fail('Package version undefined')
    # incompatibility check
    if metadata['type'] == 'plugin':
        if not check_plugin_compatibility(metadata, version):
            fail('Package incompatible with this Rudder version, please try another plugin version')
    # do not compare with exiting version to allow people to reinstall or downgrade
    return name in DB['plugins']


def check_plugin_compatibility(metadata, version):
    # check that the given version is compatible with Rudder one
    match = re.match(r'((\d+\.\d+)(\.\d+(~(beta|rc)\d+)?)?)-(\d+)\.(\d+)', metadata['version'])
    if not match:
        fail('Invalid package version ' + metadata['version'])
    rudder_version = match.group(1).replace('~beta', 'a').replace('~rc', 'b')
    rudder_major = match.group(2)
    major_version = match.group(6)
    minor_version = match.group(7)
    if rudder_major != RUDDER_MAJOR:
        return False
    if StrictVersion(rudder_version) > StrictVersion(RUDDER_VERSION):
        return False

    # check specific constraints
    full_name = metadata['name'] + '-' + metadata['version']
    if full_name in COMPATIBILITY_DB['incompatibles']:
        return False
    return True


'''Add the rudder key to a custom home for trusted gpg keys'''


def getRudderKey():
    logger.debug('check if rudder gpg key is already trusted')
    checkKeyCommand = ['/usr/bin/gpg', '--homedir', GPG_HOME, '--fingerprint']
    process = run(checkKeyCommand, check=True, capture_output=True)
    output = process[1].decode('utf-8')
    logger.debug('Looking for GPG key: %s' % GPG_RUDDER_KEY_FINGERPRINT)
    if output.find(GPG_RUDDER_KEY_FINGERPRINT) == -1:
        logger.debug('rudder gpg key was not found, adding it from %s' % (GPG_RUDDER_KEY))
        addKeyCommand = ['/usr/bin/gpg', '--homedir', GPG_HOME, '--import', GPG_RUDDER_KEY]
        logger.debug('executing %s' % (addKeyCommand))
        run(addKeyCommand, check=True)

        # Accept manually the key
        trustKeyCommand = [
            'gpg',
            '--batch',
            '--homedir',
            GPG_HOME,
            '--command-fd',
            '0',
            '--edit-key',
            '"Rudder Project"',
            'trust',
            'quit',
        ]
        logger.debug('executing %s' % (trustKeyCommand))
        run(trustKeyCommand, input=b'5\ny\nn', check=True)
    logger.debug('=> OK!')


# Indexing methods
def db_load():
    """ Load the index file into a global variable """
    global DB, COMPATIBILITY_DB
    if os.path.isfile(DB_FILE):
        with open(DB_FILE) as fd:
            DB = json.load(fd)
    if os.path.isfile(COMPATIBILITY_FILE):
        with open(COMPATIBILITY_FILE) as fd:
            COMPATIBILITY_DB = json.load(fd)


def db_save():
    """ Save the index into a file """
    with open(DB_FILE, 'w') as fd:
        json.dump(DB, fd)


def rpkg_metadata(package_file):
    cmd = ['ar', 'p', package_file, 'metadata']
    process = run(cmd, check=True, capture_output=True)
    return json.loads(process[1].decode('utf-8'))


def install_dependencies(metadata):
    dependencyToCheck = False
    packageManagerQueried = False

    # not supported yet
    has_depends = False
    depends_printed = False
    if 'depends' in metadata:
        for system in metadata['depends']:
            if system == 'binary':
                for executable in metadata['depends'][system]:
                    if distutils.spawn.find_executable(executable) is None:
                        logger.warning(
                            'The binary '
                            + executable
                            + ' was not found on the system, you must install it before installing '
                            + metadata['name']
                        )
                        return False
            else:
                try:
                    if system == 'rpm':
                        dependencyToCheck = True
                        if distutils.spawn.find_executable('rpm') is not None:
                            # this is an rpm system
                            ts = rpm.TransactionSet()
                            for package in metadata['depends']['rpm']:
                                mi = ts.dbMatch('name', package)
                                packageManagerQueried = True
                                try:
                                    h = mi.next()
                                except StopIteration:
                                    logger.warning(
                                        'The rpm package '
                                        + package
                                        + ' was not found on the system, you must install it before installing '
                                        + metadata['name']
                                    )
                                    return False
                    if system == 'apt':
                        dependencyToCheck = True
                        if distutils.spawn.find_executable('apt') is not None:
                            cache = apt.Cache()
                            packageManagerQueried = True
                            for package in metadata['depends']['apt']:
                                if not cache[package].is_installed:
                                    logger.warning(
                                        'The apt package '
                                        + package
                                        + ' was not found on the system, you must install it before installing '
                                        + metadata['name']
                                    )
                                    return False
                except Exception:
                    logger.error(
                        'Could not query rpm or apt package repository to check dependencies for this plugin.'
                    )

                if not depends_printed:
                    logger.info('This package depends on the following')
                    depends_printed = True
                logger.info('  on ' + system + ' : ' + ', '.join(metadata['depends'][system]))

                # dependency check failed
        if dependencyToCheck and not packageManagerQueried:
            logger.warning(
                'Neither rpm nor apt could be queried successfully - cannot check the dependencies for this plugin.'
            )

        if has_depends:
            logger.info('It is up to you to make sure those dependencies are installed')
    return True


def extract_archive_from_rpkg(rpkgPath, dst, archive):
    makedirs(dst)
    ar = run(['ar', 'p', rpkgPath, archive], stdout=subprocess.PIPE, check=True)
    tar = run(
        ['tar', 'xvJ', '--no-same-owner', '-C', dst], input=ar[1], check=True, capture_output=True
    )
    return tar[1].decode('utf-8')


def extract_scripts(metadata, package_file):
    package_dir = DB_DIRECTORY + '/' + metadata['name']
    extract_archive_from_rpkg(package_file, package_dir, 'scripts.txz')
    return package_dir


def run_script(name, script_dir, exist, exit_on_error=True):
    script = script_dir + '/' + name
    if os.path.isfile(script):
        if exist is None:
            param = ''
        elif exist:
            param = 'upgrade'
        else:
            param = 'install'
        run([script, param], check=True)


def jar_status(name, enable):
    global jetty_needs_restart
    text = open(PLUGINS_CONTEXT_XML).read()

    def repl(match):
        enabled = [x for x in match.group(1).split(',') if x != name and x != '']
        pprint(enabled)
        if enable:
            enabled.append(name)
        plugins = ','.join(enabled)
        return '<Set name="extraClasspath">' + plugins + '</Set>'

    text = re.sub(r'<Set name="extraClasspath">(.*?)</Set>', repl, text)
    open(PLUGINS_CONTEXT_XML, 'w').write(text)
    jetty_needs_restart = True


def remove_files(metadata):
    for filename in reversed(metadata['files']):
        # ignore already removed files
        if not os.path.exists(filename):
            logger.info('Skipping removal of ' + filename + ' as it does not exist')
            continue

        # remove old files
        if filename.endswith('/'):
            try:
                os.rmdir(filename)
            except OSError:
                pass
        else:
            os.remove(filename)


def install(metadata, package_file, exist):
    if exist:
        remove_files(DB['plugins'][metadata['name']])
    # add new files
    files = []
    for tarfile in metadata['content']:
        dest = metadata['content'][tarfile]
        file_list = extract_archive_from_rpkg(package_file, dest, tarfile)
        files.append(dest + '/')
        files.extend([dest + '/' + x for x in file_list.split('\n') if x != ''])

    metadata['files'] = files
    # update db
    DB['plugins'][metadata['name']] = metadata
    db_save()


def readConf():
    # Repos specific variables
    global URL, USERNAME, PASSWORD, PROXY_URL, PROXY_USERNAME, PROXY_PASSWORD
    logger.debug('Reading conf file %s' % (CONFIG_PATH))
    try:
        # Insert default in configuration for Python2 parsing
        configDefault = {'proxy_url': '', 'proxy_username': '', 'proxy_password': ''}
        config = configparser.RawConfigParser(configDefault)
        config.read(CONFIG_PATH)
        REPO = config.sections()[0]
        URL = config.get(REPO, 'url')
        USERNAME = config.get(REPO, 'username')
        PASSWORD = config.get(REPO, 'password')
        PROXY_URL = config.get(REPO, 'proxy_url')
        PROXY_USERNAME = config.get(REPO, 'proxy_username')
        PROXY_PASSWORD = config.get(REPO, 'proxy_password')
        makedirs(FOLDER_PATH)
        makedirs(GPG_HOME)
    except Exception as e:
        print('Could not read the conf file %s' % (CONFIG_PATH))
        fail(e)


def list_plugin_name():
    global INDEX_PATH
    try:
        pluginName = {}
        with open(INDEX_PATH) as f:
            data = json.load(f)
    except Exception as e:
        fail(str(e) + 'Could not read the index file %s' % (INDEX_PATH))
    for metadata in data:
        if metadata['name'] not in pluginName.keys():
            if 'description' in metadata:
                description = metadata['description']
                if len(description) >= 80:
                    description = description[0:76] + '...'
                pluginName[metadata['name']] = (
                    metadata['name'].replace('rudder-plugin-', ''),
                    description,
                )
            else:
                pluginName[metadata['name']] = (metadata['name'].replace('rudder-plugin-', ''), '')
    return pluginName


############# Variables #############
''' Defining global variables.'''

CONFIG_PATH = '/opt/rudder/etc/rudder-pkg/rudder-pkg.conf'
FOLDER_PATH = '/var/rudder/tmp/plugins'
INDEX_PATH = FOLDER_PATH + '/rpkg.index'
GPG_HOME = '/opt/rudder/etc/rudder-pkg'
GPG_RUDDER_KEY = '/opt/rudder/etc/rudder-pkg/rudder_plugins_key.pub'
GPG_RUDDER_KEY_FINGERPRINT = '7C16 9817 7904 212D D58C  B4D1 9322 C330 474A 19E8'

try:
    p = run(['rudder', 'agent', 'version'], capture_output=True)
    m = re.match(
        r'Rudder agent (((\d+\.\d+)\.\d+)(\.((beta|rc)\d+))?)(\.|~).*?', p[1].decode('utf-8')
    )
    RUDDER_MAJOR = m.group(3)
    RUDDER_VERSION = m.group(2)
    if m.group(4) is not None:
        RUDDER_VERSION = RUDDER_VERSION + m.group(5).replace('beta', 'a').replace('rc', 'b')
except:
    print(
        'Warning, cannot retrieve major Rudder version ! Verify that rudder is well installed on the system'
    )

# Local install specific variables
DB = {'plugins': {}}
DB_DIRECTORY = '/var/rudder/packages'
# Contains the installed package database
DB_FILE = DB_DIRECTORY + '/index.json'
# Contains known incompatible plugins (installed by the relay package)
# this is a simple list with names od the form "plugin_name-version"
COMPATIBILITY_DB = {'incompatibles': []}
COMPATIBILITY_FILE = DB_DIRECTORY + '/compatible.json'
# Plugins specific resources
PLUGINS_CONTEXT_XML = '/opt/rudder/share/webapps/rudder.xml'
LICENCES_PATH = '/opt/rudder/etc/plugins/licenses'
jetty_needs_restart = False
