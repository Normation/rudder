#####################################################################################
# Copyright 2014 Normation SAS
# Copyright 2016 Janos Mattyasovszky
#####################################################################################
#
# This program is free software: you can redistribute it and/or modify
# it under the terms of the GNU General Public License as published by
# the Free Software Foundation, Version 3.
#
# This program is distributed in the hope that it will be useful,
# but WITHOUT ANY WARRANTY; without even the implied warranty of
# MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
# GNU General Public License for more details.
#
# You should have received a copy of the GNU General Public License
# along with this program.  If not, see <http://www.gnu.org/licenses/>.
#
#####################################################################################

bundle agent compress_ldap_backups
{
  vars:
    # compres openldap-data-*.ldif files
    "backups_pattern" slist => { "openldap-data-.*\.ldif" };

  files:
    "/var/rudder/ldap/backup"
      file_select => by_name("@{backups_pattern}"),
      depth_search => recurse("0"),
      transformer => "${g.gzip} -fq \"${this.promiser}\"";

}
