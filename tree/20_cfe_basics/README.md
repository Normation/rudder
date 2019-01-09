# NCF

## 20_cfe_basics

This directory contains librairies with utility bodies that can be reused. Most notably, it contains the cfengine standard librairy.

The `cfengine` folder contains the CFEngine stdlib from 3.12.1.

The changes made are:

* Removing some files we do not use and break ncf policies: stdlib.cf, autorun.cf
* Removing some files used to manage CFEngine buhs: cfe_internal_hub.cf, cfengine_enterprise_hub_ha.cf
* Changing all body action in common.cf to take the dry_run classes into account
* Add back `_not_repaired` classes in classes_generic (https://tracker.mender.io/browse/CFE-1843)
* In packages.cf, add the test package_method and change query_updates_ifelapsed to 240 minutes
* Rename package_present and package_absent in packages.cf to `_legacy` to avoid conflict with the generic methods
