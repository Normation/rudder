#####################################################################################
# Copyright 2014 Normation SAS
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

bundle agent compress_webapp_log
{
  vars:
    # compress files older than 2 days 
    "log_compress_delay"  int  => "2";
    # compres file.log and file.log.1
    "logs_patern" slist => { ".*\.log", ".*\.log\.\d+" };

  files:
    "/var/log/rudder/webapp"
      file_select => date_pattern("${log_compress_delay}", "@{logs_patern}"),
      depth_search => recurse("0"),
      transformer => "${g.gzip} -fq \"${this.promiser}\"";

}
