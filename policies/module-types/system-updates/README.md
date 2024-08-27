# System update module

As our first module implementation in production, we chose to target the patch management feature of Rudder. On Linux it currently runs using a Python script that was assembled for the technical preview phase, but now needs to be replaced by a more industrialized solution.

The interface with the agent is a bit peculiar, as we have a dedicated reporting process for system updates. We send a JSON object in a special report type. The problem is that our custom promise type interface only allows passing CFEngine classes and an outcome status, but no variables.
To circumvent this, we pass file paths as parameters where the module will write its output.

An additional complication is that we want to capture the logs for sending them to the server, but also to display them live for debugging.

```json
{
  "software-updated": [
    {
      "name": "xz",
      "arch": "x86_64",
      "old-version": "(none):5.2.4-3.el8",
      "new-version": "(none):5.2.4-4.el8",
      "action": "updated"
    }
  ],
  "status": "repaired",
  "output": "Last metadata expiration check: 0:08:16 ago on Wed Jul  6 18:01:05 2022.\nDependencies resolved.\n=======================================================================================\n Package               Arch    Version                                 Repo        Size\n=======================================================================================\nUpgrading:\n cockpit-packagekit    noarch  272-1.el8                               appstream  630 k\n curl                  x86_64  7.61.1-22.el8.3                         baseos     352 k\n dbus                  x86_64  1:1.12.8-18.el8.1                       baseos      41 k\n dbus-common           noarch  1:1.12.8-18.el8.1                       baseos                            \n  vim-filesystem-2:8.0.1763-19.el8.4.noarch                                     \n  vim-minimal-2:8.0.1763-19.el8.4.x86_64                                        \n  xz-5.2.4-4.el8.x86_64                                                         \n  xz-devel-5.2.4-4.el8.x86_64                                                   \n  xz-libs-5.2.4-4.el8.x86_64                                                    \n\nComplete!\n"
}

```




## Design

### Logging/Output

We want to capture the logs for sending them to the server, but also to display them live for debugging.

### Package managers

We need to support the most common package managers on Linux. There are two possible approaches, either using a generic package manager interface or using an interface specific to each package manager.

Even if the first approach is more flexible, we decided to use the second approach for the following reasons:

* The package manager interface is quite simple and the code duplication is minimal.
* We can use the package manager CLI interface for debugging and understanding what happens.

#### Dnf/Yum

We use a single interface for both Dnf and Yum, as they are quite similar. We use the `yum` CLI interface, as it compatible with both package managers.

#### Zypper

#### APT

We decided to use the `libapt` C++ library through Rust bindings instead of the CLI interface. This has several advantages:

* Allow doing security-only upgrades sanely (like `unattended-upgrade`, the only official way, does), without disabling core repositories.
* Share the APT cache for the best performance and always up-to-date cache.

The drawbacks:
* Makes the build a bit more cumbersome.
* Prevents being able to run an equivalent command easily for debugging or understanding what happens.

We are on par with other tools using the `python-apt` library, which provides a similar interface.


```
Unattended upgrade result: Lock could not be acquired

Unattended-upgrades log:
Starting unattended upgrades script
Lock could not be acquired (another package manager running?)Unattended upgrade result: Lock could not be acquired

Unattended-upgrades log:
Starting unattended upgrades script
Lock could not be acquired (another package manager running?)
```
