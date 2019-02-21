#####################################################################################
# Copyright 2011 Normation SAS
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


bundle agent download_from_shared_folder_RudderUniqueID
{

  vars:

      &COPYFILE_NAME:{name |"copyfile[&i&][name]" string => "&name&";
}&

      &COPYFILE_EXCLUDE_INCLUDE:{exclude_include |"copyfile[&i&][exclude_include]" string => "&exclude_include&";
}&

      &COPYFILE_EXCLUDE_INCLUDE_OPTION:{exclude_include_option |"copyfile[&i&][exclude_include_option]" string => "&exclude_include_option&";
}&

      &COPYFILE_RECURSION:{rec |"copyfile[&i&][recursion]" string => "&rec&";
}&

      &COPYFILE_OWNER:{owner |"copyfile[&i&][owner]" string => "&owner&";
}&

      &COPYFILE_GROUP:{group |"copyfile[&i&][group]" string => "&group&";
}&

      &COPYFILE_PURGE:{purge |"copyfile[&i&][purge]" string => "&purge&";
}&

      &COPYFILE_COMPARE_METHOD:{compare_method |"copyfile[&i&][compare_method]" string => "&compare_method&";
}&

      &COPYFILE_PERM:{perms |"copyfile[&i&][perm]" string => "&perms&";
}&

      &COPYFILE_DESTINATION:{destination |"copyfile[&i&][destination]" string => "&destination&";
}&

      # Raw version for reporting
      &COPYFILE_DESTINATION:{destination |"copyfile[&i&][raw_destination]" string => "&destination&";
}&

      &COPYFILE_POST_HOOK_COMMAND:{command |"copyfile[&i&][posthook]" string => "&command&";
}&

      &TRACKINGKEY:{piuuid |"copyfile[&i&][uuid]" string => "&piuuid&";
}&
      "shared_folder" string => "&SHARED_FILES_FOLDER&";

      "canon_dirname_${index}" string => canonify(dirname("${copyfile[${index}][destination]}"));

      "index" slist => getindices("copyfile");

  iteration_1::
    "extended_modes_${index}"
      string     => "4",
      ifvarclass => "(enable_suid_${index}.!enable_sgid_${index}).(authorize_sticky_on_folder_${index}|is_file_${index})";

    "extended_modes_${index}"
      string     => "2",
      ifvarclass => "(!enable_suid_${index}.enable_sgid_${index}).(authorize_sticky_on_folder_${index}|is_file_${index})";

    "extended_modes_${index}"
      string     => "6",
      ifvarclass => "(enable_suid_${index}.enable_sgid_${index}).(authorize_sticky_on_folder_${index}|is_file_${index})";

    "extended_modes_${index}"
      string     => "0",
      ifvarclass => "(!enable_suid_${index}.!enable_sgid_${index})|!(authorize_sticky_on_folder_${index}|is_file_${index})";

  defaults:
      # Add a trailing slash to force considering destination as a directory and avoid replacing it with a file
      "copyfile[${index}][destination]" string => "${copyfile[${index}][destination]}/", if_match_regex => ".*[^\/]",
                                    ifvarclass => "pass1.!is_file_${index}";


  classes:
      "is_valid" not => strcmp("&SHARED_FILES_FOLDER&", "");

      "exist_${index}"                  expression => fileexists("${copyfile[${index}][destination]}");
      "is_file_${index}"                       not => isdir("${copyfile[${index}][destination]}");
      "copy_file_${index}_dest_notdir"         not => isdir(dirname("${copyfile[${index}][destination]}"));
      "copy_file_${index}_user_absent"         not => userexists("${copyfile[${index}][owner]}");
      "copy_file_${index}_group_absent"        not => groupexists("${copyfile[${index}][group]}");
      "need_exclusion_${index}"                and => { isvariable("copyfile[${index}][exclude_include]"), strcmp("${copyfile[${index}][exclude_include_option]}", "exclude") };
      "need_inclusion_${index}"                and => { isvariable("copyfile[${index}][exclude_include]"), strcmp("${copyfile[${index}][exclude_include_option]}", "include") };
      "apply_regex_${index}"                   or  => { "need_exclusion_${index}", "need_inclusion_${index}"};

      &COPYFILE_SUID:{suid |"enable_suid_&i&" expression => strcmp("&suid&", "true");
}&
      &COPYFILE_SGID:{sgid |"enable_sgid_&i&" expression => strcmp("&sgid&", "true");
}&
      # We authorize sticky only we recursion is level 1
      &COPYFILE_STICKY_FOLDER:{sticky_folder |"enable_sticky_bit_folder_&i&" expression => strcmp("&sticky_folder&", "true");
}&
      "recursion_only_on_folder_${index}" expression => strcmp("1", "${copyfile[${index}][recursion]}");

      "authorize_sticky_on_folder_${index}" and  => {
                                                      "recursion_only_on_folder_${index}",
                                                      "enable_sticky_bit_folder_${index}"                                                      
                                                    };

      # Set a class to define when we need to run the post-modification hook
      &COPYFILE_POST_HOOK_RUN:{run |"execute_command_&i&" expression => strcmp("&run&", "true");
}&
      "iteration_2" expression => "iteration_1";
      "iteration_1" expression => "any";


    any::
      "pass3" expression => "pass2";
      "pass2" expression => "pass1";
      "pass1" expression => "any";

  files:
    is_valid.iteration_2::

      # Suid and sgid may be setted only if the option to set it recursively, and recursion level is 1, or we are managing a file
      # If it's a directory, without exclusion
      "${copyfile[${index}][destination]}"
        copy_from    => rudder_copy_from("&SHARED_FILES_FOLDER&/${copyfile[${index}][name]}", "${server_info.policy_server}", "${copyfile[${index}][compare_method]}", "false", "false", "${copyfile[${index}][purge]}"),
        depth_search => recurse("${copyfile[${index}][recursion]}"),
        perms        => mog(
                            "${extended_modes_${index}}${copyfile[${index}][perm]}",
                            "${copyfile[${index}][owner]}",
                            "${copyfile[${index}][group]}"
                           ),
        comment      => "Enforce content of ${copyfile[${index}][destination]} based on the content on the Rudder server with ${copyfile[${index}][compare_method]} method",
        classes      => rudder_common_classes("copy_file_${index}_RudderUniqueID"),
        ifvarclass   => "(!exist_${index}|!is_file_${index}).!apply_regex_${index}";

       # If it's a directory, with exclusion
      "${copyfile[${index}][destination]}"
        copy_from    => rudder_copy_from("&SHARED_FILES_FOLDER&/${copyfile[${index}][name]}", "${server_info.policy_server}", "${copyfile[${index}][compare_method]}", "false", "false", "${copyfile[${index}][purge]}"),
        depth_search => recurse("${copyfile[${index}][recursion]}"),
        file_select  => exclude("${copyfile[${index}][exclude_include]}"),
        perms        => mog(
                            "${extended_modes_${index}}${copyfile[${index}][perm]}",
                            "${copyfile[${index}][owner]}",
                            "${copyfile[${index}][group]}"
                           ),
        comment      => "Enforce content of ${copyfile[${index}][destination]} based on the content on the Rudder server with ${copyfile[${index}][compare_method]} method",
        classes      => rudder_common_classes("copy_file_${index}_RudderUniqueID"),
        ifvarclass   => "(!exist_${index}|!is_file_${index}).need_exclusion_${index}";

       # If it's a directory, with inclusion
      "${copyfile[${index}][destination]}"
        copy_from    => rudder_copy_from("&SHARED_FILES_FOLDER&/${copyfile[${index}][name]}", "${server_info.policy_server}", "${copyfile[${index}][compare_method]}", "false", "false", "${copyfile[${index}][purge]}"),
        depth_search => recurse("${copyfile[${index}][recursion]}"),
        file_select  => by_name("${copyfile[${index}][exclude_include]}"),
        perms        => mog(
                            "${extended_modes_${index}}${copyfile[${index}][perm]}",
                            "${copyfile[${index}][owner]}",
                            "${copyfile[${index}][group]}"
                           ),
        comment      => "Enforce content of ${copyfile[${index}][destination]} based on the content on the Rudder server with ${copyfile[${index}][compare_method]} method with inclusion of '${copyfile[${index}][exclude_include]}'",
        classes      => rudder_common_classes("copy_file_${index}_RudderUniqueID"),
        ifvarclass   => "(!exist_${index}|!is_file_${index}).need_inclusion_${index}";

    # If it s a file, the depth_search prevents from enforcing the file content
    # Besides it is possible to specify suid or sgid only for a file since this
    # is too dangerous to apply suid or sgid recursively and only copy an empty
    # directory does not make sense.
    # Don't take purge in account as well.
      "${copyfile[${index}][destination]}"
        copy_from  => rudder_copy_from("&SHARED_FILES_FOLDER&/${copyfile[${index}][name]}", "${server_info.policy_server}", "${copyfile[${index}][compare_method]}", "false", "false", "false"),
        perms      => mog(
                          "${extended_modes_${index}}${copyfile[${index}][perm]}",
                          "${copyfile[${index}][owner]}",
                          "${copyfile[${index}][group]}"
                         ),
        comment    => "Enforce content of file ${copyfile[${index}][destination]} based on the content on the Rudder server with ${copyfile[${index}][compare_method]} method with exclusion of '${copyfile[${index}][exclude_include]}'",
        classes    => rudder_common_classes("copy_file_${index}_RudderUniqueID"),
        ifvarclass => "exist_${index}.is_file_${index}";

  methods:

      "any" usebundle => file_check_symlink(dirname("${copyfile[${index}][destination]}"));

    pass3.(!is_valid)::
      "any" usebundle => rudder_common_report_index("copyFile", "result_error", "${copyfile[${index}][uuid]}", "Copy file", "${copyfile[${index}][raw_destination]}", "There is no shared folder configured on the Rudder Server, so it's not possible to copy a file from it", "${index}");

    pass3.is_valid::
      "any" usebundle => rudder_common_report_index("copyFile", "result_success", "${copyfile[${index}][uuid]}", "Copy file", "${copyfile[${index}][raw_destination]}", "The content of the file(s) (copied from ${copyfile[${index}][name]}) is valid", "${index}"),
        ifvarclass => "copy_file_${index}_RudderUniqueID_kept.!copy_file_${index}_RudderUniqueID_repaired.!copy_file_${index}_RudderUniqueID_error";

      "any" usebundle => rudder_common_report_index("copyFile", "result_repaired", "${copyfile[${index}][uuid]}", "Copy file", "${copyfile[${index}][raw_destination]}", "The content or permissions of the file(s) has been repaired from source ${copyfile[${index}][name]}", "${index}"),
        ifvarclass => "copy_file_${index}_RudderUniqueID_repaired.!copy_file_${index}_RudderUniqueID_error";

      # Error conditions

      ## The destination is not an existing directory or symbolic link
      "any" usebundle => rudder_common_report_index("copyFile", "result_error", "${copyfile[${index}][uuid]}", "Copy file", "${copyfile[${index}][raw_destination]}", "The copy of the file ${copyfile[${index}][name]} failed: the destination (${copyfile[${index}][destination]}) is not stored in a valid directory", "${index}"),
        ifvarclass => "copy_file_${index}_dest_notdir.file_check_symlink_${canon_dirname_${index}}_failed";

      ## File access denied
      "any" usebundle => rudder_common_report_index("copyFile", "result_error", "${copyfile[${index}][uuid]}", "Copy file", "${copyfile[${index}][raw_destination]}", "The copy of the file failed: access to ${copyfile[${index}][name]} denied by the server", "${index}"),
        ifvarclass => "copy_file_${index}_RudderUniqueID_denied";

      ## User does not exist
      "any" usebundle => rudder_common_report_index("copyFile", "result_error", "${copyfile[${index}][uuid]}", "Copy file", "${copyfile[${index}][raw_destination]}", "The permissions could not be applied on the file: user \"${copyfile[${index}][owner]}\" not found", "${index}"),
        ifvarclass => "copy_file_${index}_user_absent";

      ## Group does not exist
      "any" usebundle => rudder_common_report_index("copyFile", "result_error", "${copyfile[${index}][uuid]}", "Copy file", "${copyfile[${index}][raw_destination]}", "The permissions could not be applied on the file: group \"${copyfile[${index}][group]}\" not found", "${index}"),
        ifvarclass => "copy_file_${index}_group_absent";

      ## Generic failure
      "any" usebundle => rudder_common_report_index("copyFile", "result_error", "${copyfile[${index}][uuid]}", "Copy file", "${copyfile[${index}][raw_destination]}", "The content or permissions of the file(s) could not have been repaired (file ${copyfile[${index}][name]} not found?)", "${index}"),
        ifvarclass => "copy_file_${index}_RudderUniqueID_error.!copy_file_${index}_dest_notdir.!copy_file_${index}_RudderUniqueID_denied.!copy_file_${index}_user_absent.!copy_file_${index}_group_absent";

    #posthook reports
      "any" usebundle => rudder_common_report_index("copyFile", "result_na", "${copyfile[${index}][uuid]}", "Post-modification hook", "${copyfile[${index}][raw_destination]}", "No post-hook command for copy of ${copyfile[${index}][name]} to ${copyfile[${index}][destination]} was defined, not executing", "${index}"),
        ifvarclass => "!execute_command_${index}";

      "any" usebundle => rudder_common_report_index("copyFile", "result_na", "${copyfile[${index}][uuid]}", "Post-modification hook", "${copyfile[${index}][raw_destination]}", "${copyfile[${index}][destination]} was already in the desired state, so no command was executed", "${index}"),
        ifvarclass => "execute_command_${index}.!copy_file_${index}_RudderUniqueID_failed.!copy_file_${index}_RudderUniqueID_repaired.copy_file_${index}_RudderUniqueID_kept";

      "any" usebundle => rudder_common_report_index("copyFile", "result_repaired", "${copyfile[${index}][uuid]}", "Post-modification hook", "${copyfile[${index}][raw_destination]}", "The post-hook command for copy of ${copyfile[${index}][name]} to ${copyfile[${index}][destination]} was correctly executed", "${index}"),
        ifvarclass => "execute_command_${index}.copyfile_posthook_${index}_RudderUniqueID_command_run_ok";

      "any" usebundle => rudder_common_report_index("copyFile", "result_error", "${copyfile[${index}][uuid]}", "Post-modification hook", "${copyfile[${index}][raw_destination]}", "The post-hook command for copy of ${copyfile[${index}][name]} to ${copyfile[${index}][destination]} couldn't be executed", "${index}"),
        ifvarclass => "execute_command_${index}.copyfile_posthook_${index}_RudderUniqueID_command_run_failed";

      # A copy_from + perms could result in any combinaision of success/repaired/failed, so we have to cover the failed.modified which results in no copy
      "any" usebundle => rudder_common_report_index("copyFile", "result_error", "${copyfile[${index}][uuid]}", "Post-modification hook", "${copyfile[${index}][raw_destination]}", "${copyfile[${index}][destination]} couldn't be copied from ${copyfile[${index}][name]}, so the post-hook command is not executed", "${index}"),
        ifvarclass => "execute_command_${index}.copy_file_${index}_RudderUniqueID_error";

  commands:
      "${copyfile[${index}][posthook]}"
        contain    => in_shell,
        classes    => if_else("copyfile_posthook_${index}_RudderUniqueID_command_run_ok", "copyfile_posthook_${index}_RudderUniqueID_command_run_failed"),
        ifvarclass => "execute_command_${index}.copy_file_${index}_RudderUniqueID_repaired.!copy_file_${index}_RudderUniqueID_error",
        comment    => "Execute the posthook command if a file was changed";

}
