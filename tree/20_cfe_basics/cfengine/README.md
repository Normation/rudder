This directory contains the CFEngine Standard Library, previously
referred to as the Common Open Promise Body Library.

Layout of this `lib` directory:

* `3.6/*.cf`: Modularized version of the CFEngine Standard Library for
  3.6.x.

* `lib/*.cf`: Re-unified library compatible with 3.7+
  - *autorun.cf*: This file contains bundles that support automatic activation
    of bundles based on tags as well as automatically adding policy files found
    in services/autorun to inputs.
  - *bundles.cf*: This file contains bundles that are generically useful. For
    example manage cron entries, recursively deleting directories, checking if
    a url is repsonding, and merging multiple data containers together.
  - *cfe_internal.cf*: This file defines bodies and bundles that are related to
    general CFEngine management. For example purging old log files.
  - *cfe_internal_hub.cf*: This file defines bodies and bundles that are
    releated to CFEngine Enterprise Hub management.
  - *cfengine_enterprise_hub_ha.cf*: This file defines bodies and bundles that
    are related to managing High Availability on CFEngine Enterprise Hubs.
  - *commands.cf*: This file contains bodies and bundles that are useful when
    running commands. For example suppressing command output, or controlling
    which user or group should be executing the command.
  - *common.cf*: This file contains bodies and bundles that are useful across
    the board. For example bodies that help to define classes based on promise
    outcomes, bodies to control logging for specific promsies, and bodies to help
    control how frequently promises get activated.
  - *databases.cf*: This file contains bodies and bundles useful when managing
    databases like Postgres and the Windows Registry.
  - *edit_xml.cf*: This file contains bodies and bundles useful when managing
    xml files.
  - *examples.cf*: This file contains examples of other useful bundles like
    activating a bundle based on probability.
  - *feature.cf*: This file defines a bundle to help manage classes to identify
    contexts that should be set or not set. It can be useful for turning
    certain aspects of policy on or off for a given amount of time.
  - *files.cf*: This file defines bodies and bundles that are useful for
    managing files.
  - *guest_environments.cf*: This file defines bodies and bundles useful for
    manageing guest environments (Virtual Machines).
  - *monitor.cf*: This file defines bodies and bundles useful when measuring
    values with cf-monitord (Enterprise only)
  - *packages.cf*: This file defines bodies and bundles releated to
    package management.
  - *paths.cf*: This file defines paths to well known binaries for
    various platforms.
  - *processes.cf*: This file defines bodies and bundles useful for
    managing processes.
  - *services.cf*: This file defines service methods for use with
    services type promises.
  - *stdlib.cf*: This file includes the commonly used library files
  - *storage.cf*: This file defines storage related bodies and bundles
    for working with mounts and volumes.
  - *users.cf*: This file defines bodies and bundles related to local
    user management
  - *vcs.cf*: This file defines bodies and bundles useful for
    interacting with version control systems.
