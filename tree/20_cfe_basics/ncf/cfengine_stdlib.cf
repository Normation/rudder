############################################################################
#  Copyright (C) Cfengine AS
#
#  This program is free software; you can redistribute it and/or modify it
#  under the terms of the GNU Lesser General Public License LGPL as published by the
#  Free Software Foundation; version 3.
#
#  This program is distributed in the hope that it will be useful,
#  but WITHOUT ANY WARRANTY; without even the implied warranty of
#  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
#  GNU General Public License for more details.
#
#  To the extent this program is licensed as part of the Enterprise
#  versions of Cfengine, the applicable Commerical Open Source License
#  (COSL) may apply to this file if you as a licensee so wish it. See
#  included file COSL.txt.
###########################################################################
#
# Cfengine Community Open Promise-Body Library
#
# This initiative started by Cfengine promotes a
# standardized set of names and promise specifications
# for template functionality within Cfengine 3.
#
# The aim is to promote an industry standard for
# naming of configuration patterns, leading to a
# de facto middleware of standardized syntax.
#
# Names should be intuitive and parameters should be
# minimal to assist readability and comprehensibility.

# Contributions to this file are voluntarily given to
# the cfengine community, and are moderated by Cfengine.
# No liability or warranty for misuse is implied.
#
# If you add to this file, please try to make the
# contributions "self-documenting". Comments made
# after the bundle/body statement are retained in
# the online docs
#

# For Cfengine Core: 3.5.0 and older

###################################################
# If you find Cfengine useful, please consider    #
# purchasing a commercial version of the software.#
###################################################

###################################################
# edit_line bundles
###################################################

bundle edit_line insert_lines(lines)
{
insert_lines:

  "$(lines)"
    comment => "Append lines if they don't exist";
}

##

bundle edit_line insert_file(templatefile)
{
insert_lines:

   "$(templatefile)"
            comment => "Insert the template file into the file being edited",
        insert_type => "file";
}

##

bundle edit_line comment_lines_matching(regex,comment)

 # Comment lines of a file matching a regex

{
replace_patterns:

 "^($(regex))$"

     replace_with => comment("$(comment)"),
          comment => "Search and replace string";
}

##

bundle edit_line uncomment_lines_matching(regex,comment)

 # Uncomment lines of a file where the regex matches
 # the text after the comment string

{
replace_patterns:

 "^$(comment)\s?($(regex))$"

       replace_with => uncomment,
            comment => "Uncomment lines matching a regular expression";
}

##

bundle edit_line comment_lines_containing(regex,comment)

 # Comment lines of a file containing a regex

{
replace_patterns:

 "^((?!$(comment)).*$(regex).*)$"

     replace_with => comment("$(comment)"),
          comment => "Comment out lines in a file";
}

##

bundle edit_line uncomment_lines_containing(regex,comment)

 # Uncomment lines of a file where the regex matches
 # the text after the comment string

{
replace_patterns:

 "^$(comment)\s?(.*$(regex).*)$"

    replace_with => uncomment,
         comment => "Uncomment a line containing a fragment";
}

##

bundle edit_line delete_lines_matching(regex)
{
delete_lines:

  "$(regex)"

     comment => "Delete lines matching regular expressions";
}

##

bundle edit_line warn_lines_matching(regex)
{
delete_lines:

  "$(regex)"

   comment => "Warn about lines in a file",
    action => warn_only;
}

##

bundle edit_line append_if_no_line(str)
{
insert_lines:

 "$(str)"

     comment => "Append a line to the file if it doesn't already exist";
}

##

bundle edit_line append_if_no_lines(list)
{
insert_lines:

 "$(list)"

   comment => "Append lines to the file if they don't already exist";
}

##

bundle edit_line replace_line_end(start,end)
#
# Lines starting with "$(start)" will get the ending given in "$(end)",
# whitespaces will be left unmodified.
# For example, replace_line_end("ftp", "2121/tcp") would replace
# "ftp             21/tcp"
# with
# "ftp             2121/tcp"
{
field_edits:

   "\s*$(start)\s.*"
         comment => "Replace lines with $(this.start) and $(this.end)",
      edit_field => line("(^|\s)$(start)\s*", "2", "$(end)","set");
}

##

bundle edit_line append_to_line_end(start,end)
#
# Lines starting with "$(start)" and not ending with "$(end)"
# will get appended with "$(end)", whitespaces will be left unmodified.
# For example, append_to_line_end("kernel", "vga=791") would replace
# "kernel /boot/vmlinuz root=/dev/sda7"
# with
# "kernel /boot/vmlinuz root=/dev/sda7 resume=/dev/sda9 vga=791"
#
# WARNING: Be careful not to have multiple promises matching the same line,
#          which would result in the line growing indefinetively.
{
field_edits:

   "\s*$(start)\s.*"
         comment => "Append lines with $(this.start) and $(this.end)",
      edit_field => line("(^|\s)$(start)\s*", "2", "$(end)","append");
}

##

bundle edit_line regex_replace(find,replace)
# You can think of this like a PCRE powered sed.
# Find exactly a regular expression and replace exactly the match with a string.
{
replace_patterns:

 "$(find)"
     replace_with => value("$(replace)"),
          comment => "Search and replace string";
}

##

bundle edit_line resolvconf(search,list)

 # search is the search domains with space
 # list is an slist of nameserver addresses

{
delete_lines:

  "search.*"     comment => "Reset search lines from resolver";
  "nameserver.*" comment => "Reset nameservers in resolver";

insert_lines:

  "search $(search)"    comment => "Add search domains to resolver";
  "nameserver $(list)"  comment => "Add name servers to resolver";
}

##

bundle edit_line manage_variable_values_ini(tab, sectionName)

 # Sets the RHS of configuration items in the file of the form
 # LHS=RHS
 # If the line is commented out with #, it gets uncommented first.
 # Adds a new line if none exists.
 # Removes any variable value pairs not defined for the ini section
 # The argument is an associative array containing tab[SectionName][LHS]="RHS"
 # don't change value when the RHS is dontchange

 # Based on set_variable_values_ini
 # Added delete lines section to empty out undefined variable values for INI section

 # CAUTION : for it to work nicely, you should use Cfengine with the commit n°3229
 # otherwise you may risk a segfault

 #
 # If you are running 3.2.1 or earlier or more specifically git commit # or
 # earlier you can use this to work around the segfault bug.
 # vars:
 #     "$(file)"
 #         edit_line => append_if_no_line("[#EOF#]"),
 #         create => "true",
 #         comment => "Work around bug<bug#here> where eof did not mean end
 #                     of section and thus cannot select a region. This promise
 #                     should be placed before your call to this bundle";
 #      "$(file)"
 #         edit_line   => manage_variable_values_ini("context.array", "SectionName"),
 #         create => "true",
 #         comment     => "Set the variale values only in the specified ini region";
 #

{
    vars:
        "index" slist => getindices("$(tab)[$(sectionName)]");

    # Be careful if the index string contains funny chars
        "cindex[$(index)]" string => canonify("$(index)");

    classes:
        "edit_$(cindex[$(index)])"     not => strcmp("$($(tab)[$(sectionName)][$(index)])","dontchange"),
                                   comment => "Create conditions to make changes";

    field_edits:

    # If the line is there, but commented out, first uncomment it
        "#+\s*$(index)\s*=.*"
            select_region => INI_section("$(sectionName)"),
            edit_field => col("=","1","$(index)","set"),
            ifvarclass => "edit_$(cindex[$(index)])";

    # match a line starting like the key something
        "$(index)\s*=.*"
            edit_field => col("=","2","$($(tab)[$(sectionName)][$(index)])","set"),
            select_region => INI_section("$(sectionName)"),
            classes => if_ok("manage_variable_values_ini_not_$(cindex[$(index)])"),
            ifvarclass => "edit_$(cindex[$(index)])";

    delete_lines:
        ".*"
            select_region => INI_section("$(sectionName)"),
            comment       => "Remove all entries in the region so there are no extra entries";

    insert_lines:
        "[$(sectionName)]"
                location => start,
                 comment => "Insert lines";

        "$(index)=$($(tab)[$(sectionName)][$(index)])"
                select_region => INI_section("$(sectionName)"),
                ifvarclass => "!manage_variable_values_ini_not_$(cindex[$(index)]).edit_$(cindex[$(index)])";

}

##

bundle edit_line set_variable_values_ini(tab, sectionName)

 # Sets the RHS of configuration items in the file of the form
 # LHS=RHS
 # If the line is commented out with #, it gets uncommented first.
 # Adds a new line if none exists.
 # The argument is an associative array containing tab[SectionName][LHS]="RHS"
 # don't change value when the RHS is dontchange

 # Based on set_variable_values from cfengine_stdlib.cf, modified to
 # use section to define were to write, and to handle commented-out lines.

 # CAUTION : for it to work nicely, you should use Cfengine with the commit n°3229
 # otherwise you may risk a segfault

 #
 # If you are running 3.2.1 or earlier or more specifically git commit # or
 # earlier you can use this to work around the segfault bug.
 # vars:
 #     "$(file)"
 #         edit_line => append_if_no_line("[#EOF#]"),
 #         create => "true",
 #         comment => "Work around bug<bug#here> where eof did not mean end
 #                     of section and thus cannot select a region. This promise
 #                     should be placed before your call to this bundle";
 #      "$(file)"
 #         edit_line   => set_variable_values_ini("context.array", "SectionName"),
 #         create => "true",
 #         comment     => "Set the variale values only in the specified ini region";
 #

{
    vars:
        "index" slist => getindices("$(tab)[$(sectionName)]");

    # Be careful if the index string contains funny chars
        "cindex[$(index)]" string => canonify("$(index)");

    classes:
        "edit_$(cindex[$(index)])"     not => strcmp("$($(tab)[$(sectionName)][$(index)])","dontchange"),
                                   comment => "Create conditions to make changes";

    field_edits:

    # If the line is there, but commented out, first uncomment it
        "#+\s*$(index)\s*=.*"
            select_region => INI_section("$(sectionName)"),
            edit_field => col("=","1","$(index)","set"),
            ifvarclass => "edit_$(cindex[$(index)])";

    # match a line starting like the key something
        "$(index)\s*=.*"
            edit_field => col("=","2","$($(tab)[$(sectionName)][$(index)])","set"),
            select_region => INI_section("$(sectionName)"),
            classes => if_ok("set_variable_values_ini_not_$(cindex[$(index)])"),
            ifvarclass => "edit_$(cindex[$(index)])";

    insert_lines:
        "[$(sectionName)]"
                location => start,
                 comment => "Insert lines";

        "$(index)=$($(tab)[$(sectionName)][$(index)])"
                select_region => INI_section("$(sectionName)"),
                ifvarclass => "!set_variable_values_ini_not_$(cindex[$(index)]).edit_$(cindex[$(index)])";

}

bundle edit_line set_quoted_values(v) 
{
# Sets the RHS of variables in shell-like files
#   that is:
#      LHS="RHS"
# Adds a new line if no LHS exists
# repairs RHS values if one does exist
# If the line is commented out with #, it gets uncommented first.
#
# To use:
#   1) Define an array, where the keys are the LHS and the values are the RHS
#        "stuff[lhs-1]" string => "rhs1";
#        "stuff[lhs-2]" string => "rhs2";
#   2) The parameter passed to the edit_line promise is the fully qualified
#      name of the array (i.e., "bundlename.stuff") WITHOUT any "$" or "@"

vars:
	"index" slist => getindices("$(v)");
	# Be careful if the index string contains funny chars

	"cindex[$(index)]" string => canonify("$(index)");

field_edits:
	# If the line is there, but commented out, first uncomment it
	"#+\s*$(index)\s*=.*"
		edit_field => col("=","1","$(index)","set");

	# match a line starting like the key = something
	"\s*$(index)\s*=.*"
		edit_field => col("=","2",'"$($(v)[$(index)])"',"set"),
		classes    => if_ok("$(cindex[$(index)])_in_file"),
		comment    => "Match a line starting like key = something";

insert_lines:
        '$(index)="$($(v)[$(index)])"'
		comment    => "Insert a variable definition",
		ifvarclass => "!$(cindex[$(index)])_in_file";
}

##

bundle edit_line set_variable_values(v)

 # Sets the RHS of variables in the file of the form
 #   LHS = RHS
 # Adds a new line if no LHS exists, repairs RHS values if one does exist
 #
 # To use:
 #   1) Define an array, where the keys are the LHS and the values are the RHS
 #        "stuff[lhs-1]" string => "rhs1";
 #        "stuff[lhs-2]" string => "rhs2";
 #   2) The parameter passed to the edit_line promise is the fully qualified
 #      name of the array (i.e., "bundlename.stuff") WITHOUT any "$" or "@"

{
vars:

  "index" slist => getindices("$(v)");

  # Be careful if the index string contains funny chars

  "cindex[$(index)]" string => canonify("$(index)");
  "cv"               string => canonify("$(v)");

field_edits:

  # match a line starting like the key = something

  "\s*$(index)\s*=.*"

     edit_field => col("=","2","$($(v)[$(index)])","set"),
        classes => if_ok("$(cv)_$(cindex[$(index)])_in_file"),
        comment => "Match a line starting like key = something";

insert_lines:

  "$(index)=$($(v)[$(index)])"

         comment => "Insert a variable definition",
      ifvarclass => "!$(cv)_$(cindex[$(index)])_in_file";
}

bundle edit_line set_config_values(v)

 # Sets the RHS of configuration items in the file of the form
 #   LHS RHS
 # If the line is commented out with #, it gets uncommented first.
 # Adds a new line if none exists.
 # The argument is the fully-qualified name of an associative array containing v[LHS]="rhs"

{
vars:
  "index" slist => getindices("$(v)");

  # Be careful if the index string contains funny chars
  "cindex[$(index)]" string => canonify("$(index)");

replace_patterns:
  # If the line is there, maybe commented out, uncomment and replace with
  # the correct value
  "^\s*($(index)\s+(?!$($(v)[$(index)])$).*|# ?$(index)\s+.*)$"
         comment => "Correct the value",
    replace_with => value("$(index) $($(v)[$(index)])"),
         classes => always("replace_attempted_$(cindex[$(index)])");

insert_lines:
  "$(index) $($(v)[$(index)])"
    ifvarclass => "replace_attempted_$(cindex[$(index)])";

}

bundle edit_line set_config_values_matching(v,pat)

 # Sets the RHS of configuration items in the file of the form
 #   LHS RHS
 # If the line is commented out with #, it gets uncommented first.
 # Adds a new line if none exists.
 # Only elements of "v" that match the regex "pat" are used
 # The argument is the fully-qualified name of an associative array containing v[LHS]="rhs"

{
vars:
  "allparams" slist => getindices("$(v)");
  "index"     slist => grep("$(pat)", "allparams");

  # Be careful if the index string contains funny chars
  "cindex[$(index)]" string => canonify("$(index)");

replace_patterns:
    # If the line is there, maybe commented out, uncomment and replace with
    # the correct value
    "^\s*($(index)\s+(?!$($(v)[$(index)])).*|# ?$(index)\s+.*)$"
           comment => "Correct the value",
      replace_with => value("$(index) $($(v)[$(index)])"),
           classes => always("replace_attempted_$(cindex[$(index)])");

insert_lines:
  "$(index) $($(v)[$(index)])"
    ifvarclass => "replace_attempted_$(cindex[$(index)])";

}

##

bundle edit_line maintain_key_values(v,sep)

 # Contributed by David Lee
 # Purpose: Sets the RHS of configuration items with an giving separator

{
  vars:
    "index" slist => getindices("$(v)");
    # Be careful if the index string contains funny chars
    "cindex[$(index)]" string => canonify("$(index)");
    # Matching pattern for line (basically key-and-separator)
    "keypat[$(index)]" string => "\s*$(index)\s*$(sep)\s*";

    # Values may contain regexps. Escape them for replace_pattern matching.
    "ve[$(index)]" string => escape("$($(v)[$(index)])");

  classes:
    "$(cindex[$(index)])_key_in_file"
       comment => "Dynamic Class created if patterns matching",
       expression => regline("^$(keypat[$(index)]).*", "$(edit.filename)");

  replace_patterns:
    # For convergence need to use negative lookahead on value:
    # "key sep (?!value).*"
    "^($(keypat[$(index)]))(?!$(ve[$(index)])$).*"
      comment => "Replace definition of $(index)",
      replace_with => value("$(match.1)$($(v)[$(index)])");

  insert_lines:
    "$(index)$(sep)$($(v)[$(index)])"
      comment => "Insert definition of $(index)",
      ifvarclass => "!$(cindex[$(index)])_key_in_file";
}

##

bundle edit_line append_users_starting(v)

 # For adding to /etc/passwd or etc/shadow, needs
 # an array v[username] string => "line..."

{
vars:

  "index"        slist => getindices("$(v)");

classes:

  "add_$(index)"     not => userexists("$(index)"),
                 comment => "Class created if user does not exist";

insert_lines:

  "$($(v)[$(index)])"

         comment => "Append users into a password file format",
      ifvarclass => "add_$(index)";
}

##

bundle edit_line append_groups_starting(v)

 # For adding groups to /etc/group, needs
 # an array v[groupname] string => "line..."

{
vars:

  "index"        slist => getindices("$(v)");

classes:

  "add_$(index)"     not => groupexists("$(index)"),
                 comment => "Class created if group does not exist";

insert_lines:

  "$($(v)[$(index)])"

         comment => "Append users into a group file format",
      ifvarclass => "add_$(index)";

}

##

bundle edit_line set_colon_field(key,field,val)

 # Set the value of field number "field" of the
 # line whose first field is "key", in a colon-separated file.

{
field_edits:

  "$(key):.*"

       comment => "Edit a colon-separated file, using the first field as a key",
     edit_field => col(":","$(field)","$(val)","set");
}

##

bundle edit_line set_user_field(user,field,val)

 # Set the value of field number "field" in
 # a :-field formatted file like /etc/passwd

{
field_edits:

 "$(user):.*"

        comment => "Edit a user attribute in the password file",
     edit_field => col(":","$(field)","$(val)","set");
}

##

bundle edit_line append_user_field(group,field,allusers)

 # For adding users to to a file like /etc/group
 # at field position "field", comma separated subfields

{
vars:

  "val" slist => { @(allusers) };

field_edits:

 "$(group):.*"

       comment => "Append users into a password file format",
    edit_field => col(":","$(field)","$(val)","alphanum");
}

##

bundle edit_line expand_template(templatefile)

 # Read in the named text file and expand $(var)
 # inside the file

{
insert_lines:

   "$(templatefile)"

        insert_type => "file",
            comment => "Expand variables in the template file",
     expand_scalars => "true";
}

bundle edit_line replace_or_add(pattern,line)

 # Replace a pattern in a file with a single line.
 # If the pattern is not found, add the line to the file.
 # The pattern must match the whole line (it is automatically
 # anchored to the start and end of the line) to avoid
 # ambiguity.

{
vars:
  "cline" string => canonify("$(line)");
  "eline" string => escape("$(line)");

replace_patterns:
  "^(?!$(eline)$)$(pattern)$"
          comment => "Replace a pattern here",
     replace_with => value("$(line)"),
          classes => always("replace_done_$(cline)");

insert_lines:
  "$(line)"
    ifvarclass => "replace_done_$(cline)";
}

##

bundle agent cronjob(commands,user,hours,mins)

 # For adding lines to crontab for a user
 # methods:
 #  "cron" usebundle => cronjob("/bin/ls","mark","*","5,10");

{
vars:
  SuSE::
   "crontab" string => "/var/spool/cron/tabs";
  redhat|fedora::
   "crontab" string => "/var/spool/cron";
  freebsd::
   "crontab" string => "/var/cron/tabs";
 !(SuSE|redhat|fedora|freebsd)::
    "crontab" string => "/var/spool/cron/crontabs";

files:

!windows::
  "$(crontab)/$(user)"

    comment => "A user's regular batch jobs are added to this file",
     create => "true",
  edit_line => append_if_no_line("$(mins) $(hours) * * * $(commands)"),
      perms => mo("600","$(user)"),
    classes => if_repaired("changed_crontab");

processes:

changed_crontab::
   "cron"
         comment => "Most crons need to be huped after file changes",
         signals => { "hup" };

}



##-------------------------------------------------------
## editing bodies
##-------------------------------------------------------

body edit_field quoted_var(newval,method)
{
field_separator => "\"";
select_field    => "2";
value_separator  => " ";
field_value     => "$(newval)";
field_operation => "$(method)";
extend_fields => "false";
allow_blank_fields => "true";
}

##

body edit_field col(split,col,newval,method)
{
field_separator    => "$(split)";
select_field       => "$(col)";
value_separator    => ",";
field_value        => "$(newval)";
field_operation    => "$(method)";
extend_fields      => "true";
allow_blank_fields => "true";
}

##

body edit_field line(split,col,newval,method)
{
field_separator    => "$(split)";
select_field       => "$(col)";
value_separator    => " ";
field_value        => "$(newval)";
field_operation    => "$(method)";
extend_fields      => "true";
allow_blank_fields => "true";
}

##

body replace_with value(x)
{
replace_value => "$(x)";
occurrences => "all";
}

##

body select_region INI_section(x)
{
select_start => "\[$(x)\]\s*";
select_end => "\[.*\]\s*";
}

##-------------------------------------------------------
## edit_defaults
##-------------------------------------------------------

body edit_defaults std_defs
{
empty_file_before_editing => "false";
edit_backup => "false";
#max_file_size => "300000";
}

##

body edit_defaults empty
{
empty_file_before_editing => "true";
edit_backup => "false";
#max_file_size => "300000";
}

##

body edit_defaults no_backup
{
edit_backup => "false";
}

##

body edit_defaults backup_timestamp
{
empty_file_before_editing => "false";
edit_backup => "timestamp";
#max_file_size => "300000";
}

##-------------------------------------------------------
## location
##-------------------------------------------------------

body location start
{
before_after => "before";
}

##

body location after(str)
{
before_after => "after";
select_line_matching => "$(str)";
}

##

body location before(str)
{
before_after => "before";
select_line_matching => "$(str)";
}


##-------------------------------------------------------
## replace_with
##-------------------------------------------------------

##

body replace_with comment(c)
{
replace_value => "$(c) $(match.1)";
occurrences => "all";
}

##

body replace_with uncomment
{
replace_value => "$(match.1)";
occurrences => "all";
}

####################################################
## agent bodyparts
####################################################

##-------------------------------------------------------
## action
##-------------------------------------------------------

body action if_elapsed(x)
{
ifelapsed => "$(x)";
expireafter => "$(x)";
}

##

body action if_elapsed_day
{
ifelapsed => "1440";    # 60 x 24
expireafter => "1400";
}

##

body action measure_performance(x)
{
measurement_class => "Detect changes in $(this.promiser)";
ifelapsed => "$(x)";
expireafter => "$(x)";
}

##

body action warn_only
{
action_policy => "warn";
ifelapsed => "60";
}

##

body action bg(elapsed,expire)
{
ifelapsed   => "$(elapsed)";
expireafter => "$(expire)";
background  => "true";
}

##

body action ifwin_bg
{
windows::
background => "true";
}

##

body action immediate
{
ifelapsed => "0";
}

##

body action policy(p)
{
action_policy => "$(p)";
}

##

# Log a message to log=[/file|stdout]

body action log_repaired(log,message)
{
log_string => "$(sys.date), $(message)";
log_repaired => "$(log)";
}

###

body action log_verbose
{
log_level => "verbose";
}

##-------------------------------------------------------
## contain
##-------------------------------------------------------

body contain silent
{
no_output => "true";
}

##

body contain in_dir(s)
{
chdir => "$(s)";
}

##

body contain in_dir_shell(s)
{
chdir => "$(s)";
useshell => "true"; # canonical "useshell" but this is backwards-compatible
}

##

body contain silent_in_dir(s)
{
chdir => "$(s)";
no_output => "true";
}

##

body contain in_shell
{
useshell => "true"; # canonical "useshell" but this is backwards-compatible
}

##

body contain in_shell_bg
{
useshell => "true"; # canonical "useshell" but this is backwards-compatible
}

##

body contain in_shell_and_silent
{
useshell => "true"; # canonical "useshell" but this is backwards-compatible
no_output => "true";
}

##

body contain in_dir_shell_and_silent(dir)
{
useshell => "true"; # canonical "useshell" but this is backwards-compatible
no_output => "true";
chdir => "$(dir)";
}

##

body contain setuid(x)
{
exec_owner => "$(x)";
useshell => "false"; # canonical "noshell" but this is backwards-compatible
}

##

body contain setuid_sh(x)
{
exec_owner => "$(x)";
useshell => "true"; # canonical "useshell" but this is backwards-compatible
}

##

body contain setuidgid_sh(owner,group)
{
exec_owner => "$(owner)";
exec_group => "$(group)";
useshell => "true"; # canonical "useshell" but this is backwards-compatible
}

##

body contain jail(owner,root,dir)
{
exec_owner => "$(owner)";
useshell => "true"; # canonical "useshell" but this is backwards-compatible
chdir => "$(dir)";
chroot => "$(root)";
}

##

body contain setuid_umask(uid, umask)
###################################################
#       | Files               | Directories       #
###################################################
# Umask | Octal   Symbolic    | Octal   Symbolic  #
########+#####################+###################+
# 000   | 666     (rw-rw-rw-) | 777     (rwxrwxrwx)
# 002   | 664     (rw-rw-r--) | 775     (rwxrwxr-x)
# 022   | 644     (rw-r--r--) | 755     (rwxr-xr-x)
# 027   | 640     (rw-r-----) | 750     (rwxr-x---)
# 077   | 600     (rw-------) | 700     (rwx------)
# 277   | 400     (r--------) | 500     (r-x------)
{
exec_owner => "$(uid)";
umask => "$(umask)";
}


##-------------------------------------------------------
## classes
##-------------------------------------------------------


body classes if_repaired(x)
{
promise_repaired => { "$(x)" };
}

##

body classes if_else(yes,no)

{
promise_kept     => { "$(yes)" };
promise_repaired => { "$(yes)" };
repair_failed    => { "$(no)" };
repair_denied    => { "$(no)" };
repair_timeout   => { "$(no)" };
}

##

body classes cf2_if_else(yes,no)

# meant to match cf2 semantics

{
promise_repaired => { "$(yes)" };
repair_failed    => { "$(no)" };
repair_denied    => { "$(no)" };
repair_timeout   => { "$(no)" };
}

##

body classes if_notkept(x)
{
repair_failed   => { "$(x)" };
repair_denied   => { "$(x)" };
repair_timeout  => { "$(x)" };
}

##

body classes if_ok(x)
{
promise_repaired => { "$(x)" };
promise_kept => { "$(x)" };
}

##

body classes if_ok_cancel(x)
{
cancel_repaired => { "$(x)" };
cancel_kept => { "$(x)" };
}

##

body classes cmd_repair(code,cl)
{
repaired_returncodes => { "$(code)" };
promise_repaired => { "$(cl)" };
}

body classes classes_generic(x)
# Define x prefixed/suffixed with promise outcome
{
  promise_repaired => { "promise_repaired_$(x)", "$(x)_repaired", "$(x)_ok", "$(x)_reached" };
  repair_failed => { "repair_failed_$(x)", "$(x)_failed", "$(x)_not_ok", "$(x)_not_kept", "$(x)_not_repaired", "$(x)_reached" };
  repair_denied => { "repair_denied_$(x)", "$(x)_denied", "$(x)_not_ok", "$(x)_not_kept", "$(x)_not_repaired", "$(x)_reached" };
  repair_timeout => { "repair_timeout_$(x)", "$(x)_timeout", "$(x)_not_ok", "$(x)_not_kept", "$(x)_not_repaired", "$(x)_reached" };
  promise_kept => { "promise_kept_$(x)", "$(x)_kept", "$(x)_ok", "$(x)_not_repaired", "$(x)_reached" };
}

##-------------------------------------------------------
## Persistent classes
##-------------------------------------------------------

body classes state_repaired(x)
{
promise_repaired => { "$(x)" };
persist_time => "10";
}

##

body classes enumerate(x)

#
# This is used by commercial editions to count
# instances of jobs in a cluster
#

{
promise_repaired => { "mXC_$(x)" };
promise_kept => { "mXC_$(x)" };
persist_time => "15";
}

##

body classes always(x)

# Define a class no matter what the outcome of the promise is

{
  promise_repaired => { "$(x)" };
  promise_kept => { "$(x)" };
  repair_failed => { "$(x)" };
  repair_denied => { "$(x)" };
  repair_timeout => { "$(x)" };
}

###################################################
# agent bundles
###################################################


##..................................................
## files promises
##..................................................

# this is a workaround for the issue that recurse_with_base precludes
# a file from being deleted
bundle agent rm_rf(name)
{
  classes:
      "isdir" expression => isdir($(name));
  files:
    isdir::
      "$(name)"
      file_select => all,
      depth_search => recurse_with_base(999),
      delete => tidy;

    !isdir::
      "$(name)" delete => tidy;
}

##-------------------------------------------------------
## copy_from
##-------------------------------------------------------

body copy_from secure_cp(from,server)
{
source      => "$(from)";
servers     => { "$(server)" };
compare     => "digest";
encrypt     => "true";
verify      => "true";
}

##

body copy_from remote_cp(from,server)
{
servers     => { "$(server)" };
source      => "$(from)";
compare     => "mtime";
}

##

body copy_from remote_dcp(from,server)
{
servers     => { "$(server)" };
source      => "$(from)";
compare     => "digest";
}

##

body copy_from local_cp(from)
{
source      => "$(from)";
}

##

body copy_from local_dcp(from)
{
source      => "$(from)";
compare     => "digest";
}

##

body copy_from perms_cp(from)
{
source      => "$(from)";
preserve    => "true";
}

body copy_from backup_local_cp(from)
# Local copy, keeping a backup of old versions
{
  source      => "$(from)";
  copy_backup => "timestamp";
}

##

# Copy only if the file does not already exist, i.e. seed the placement

body copy_from seed_cp(from)
{
source      => "$(from)";
compare     => "exists";
}

##

body copy_from sync_cp(from,server)
{
servers     => { "$(server)" };
source      => "$(from)";
purge       => "true";
preserve    => "true";
type_check  => "false";
}

##

body copy_from no_backup_cp(from)
{
source      => "$(from)";
copy_backup => "false";
}

##

body copy_from no_backup_dcp(from)
{
source      => "$(from)";
copy_backup => "false";
compare     => "digest";
}

##

body copy_from no_backup_rcp(from,server)
{
servers     => { "$(server)" };
source      => "$(from)";
compare     => "mtime";
copy_backup => "false";
}

##-------------------------------------------------------
## link_from
##-------------------------------------------------------

body link_from ln_s(x)
{
link_type => "symlink";
source => "$(x)";
when_no_source => "force";
}

##

body link_from linkchildren(tofile)
{
source        => "$(tofile)";
link_type     => "symlink";
when_no_source  => "force";
link_children => "true";
when_linking_children => "if_no_such_file"; # "override_file";
}

##-------------------------------------------------------
## perms
##-------------------------------------------------------

body perms m(mode)
{
mode   => "$(mode)";
}

##

body perms mo(mode,user)
{
owners => { "$(user)" };
mode   => "$(mode)";
}

##

body perms mog(mode,user,group)
{
owners => { "$(user)" };
groups => { "$(group)" };
mode   => "$(mode)";
}

##

body perms og(u,g)
{
owners => { "$(u)" };
groups => { "$(g)" };
}

##

body perms owner(user)
{
owners => { "$(user)" };
}

##-------------------------------------------------------
## ACLS (extended Unix perms)
##-------------------------------------------------------

body acl access_generic(acl)
# default/inherited ACLs are left unchanged,
# applicable for both files and directories on all platforms
{
acl_method => "overwrite";
aces => { "@(acl)" };

windows::
acl_type => "ntfs";

!windows::
acl_type => "posix";
}

##

body acl ntfs(acl)
{
acl_type => "ntfs";
acl_method => "overwrite";
aces => { "@(acl)" };
}

##

body acl strict
# NOTE: May need to take ownership of file/dir
# to be sure no-one else is allowed access
{
acl_method => "overwrite";

windows::
aces => { "user:Administrator:rwx" };
!windows::
aces => { "user:root:rwx" };
}

##-------------------------------------------------------
## depth_search
##-------------------------------------------------------

body depth_search recurse(d)

{
depth => "$(d)";
xdev  => "true";
}

##

body depth_search recurse_ignore(d,list)
{
depth => "$(d)";
exclude_dirs => { @(list) };
}

##

body depth_search include_base
{
include_basedir => "true";
}

body depth_search recurse_with_base(d) 
{
	depth => "$(d)";
	xdev  => "true";
	include_basedir => "true";
}

##-------------------------------------------------------
## delete
##-------------------------------------------------------

body delete tidy

{
dirlinks => "delete";
rmdirs   => "true";
}

##-------------------------------------------------------
## rename
##-------------------------------------------------------

body rename disable
{
disable => "true";
}

##

body rename rotate(level)
{
rotate => "$(level)";
}

##

body rename to(file)
{
newname => "$(file)";
}

##-------------------------------------------------------
## file_select
##-------------------------------------------------------

body file_select name_age(name,days)
{
leaf_name   => { "$(name)" };
mtime       => irange(0,ago(0,0,"$(days)",0,0,0));
file_result => "mtime.leaf_name";
}

##

body file_select days_old(days)
{
mtime       => irange(0,ago(0,0,"$(days)",0,0,0));
file_result => "mtime";
}

##

body file_select size_range(from,to)
{
search_size => irange("$(from)","$(to)");
file_result => "size";
}

##

body file_select exclude(name)
{
leaf_name  => { "$(name)"};
file_result => "!leaf_name";
}

##

body file_select plain
{
file_types  => { "plain" };
file_result => "file_types";
}

body file_select dirs
{
file_types  => { "dir" };
file_result => "file_types";
}

##

body file_select by_name(names)
{
leaf_name  => { @(names)};
file_result => "leaf_name";
}

##

body file_select ex_list(names)
{
leaf_name  => { @(names)};
file_result => "!leaf_name";
}

##

body file_select all
{
leaf_name => { ".*" };
file_result => "leaf_name";
}

##

body file_select older_than(years, months, days, hours, minutes, seconds)
# Generic older_than selection body, aimed to have a common definition handy
# for every case possible.
{
mtime       => irange(0,ago("$(years)","$(months)","$(days)","$(hours)","$(minutes)","$(seconds)"));
file_result => "mtime";
}

##

body file_select filetype_older_than(filetype, days)
# Select files of specified type older than specified number of days
# Note: This body only takes a single filetype, see filetypes_older_than
#       if you want to select more than one type of file
{
file_types => { "$(filetype)" };
mtime      => irange(0,ago(0,0,"$(days)",0,0,0));
file_result => "file_types.mtime";
}

##

body file_select filetypes_older_than(filetypes, days)
# Select files of specified type older than specified number of days
# Note: This body only takes a list of filetypes
{
file_types => { @(filetypes) };
mtime      => irange(0,ago(0,0,"$(days)",0,0,0));
file_result => "file_types.mtime";
}

##-------------------------------------------------------
## changes
##-------------------------------------------------------

body changes detect_all_change

# This is fierce, and will cost disk cycles

{
hash           => "best";
report_changes => "all";
update_hashes  => "yes";
}

##

body changes detect_all_change_using(hash)

# Detect all changes using a configurable hashing algorithm
# for times when you care about both content and file stats e.g. mtime
# hash - supported hashing algorithm (md5, sha1, sha224, sha256, sha384,
#   sha512, best)

{
hash           => "$(hash)";
report_changes => "all";
update_hashes  => "yes";
}

##

body changes detect_content

# This is a cheaper alternative

{
hash           => "md5";
report_changes => "content";
update_hashes  => "yes";
}

##

body changes detect_content_using(hash)

# Detect content changes using a configurable hashing algorithm
# for times when you only care about content, not file stats e.g. mtime
# hash - supported hashing algorithm (md5, sha1, sha224, sha256, sha384,
#   sha512, best)

{
hash           => "$(hash)";
report_changes => "content";
update_hashes  => "yes";
}

##

body changes noupdate
# Use on (small) files that should never change
{
hash           => "sha256";
report_changes => "content";
update_hashes  => "no";
}

##

body changes diff
# Generates diff report (Nova and above)
{
hash           => "sha256";
report_changes => "content";
report_diffs   => "true";
update_hashes  => "yes";
}

##

body changes all_changes
# Generates diff report (Nova and above)
{
hash           => "sha256";
report_changes => "all";
report_diffs   => "true";
update_hashes  => "yes";
}

##

body changes diff_noupdate
{
hash           => "sha256";
report_changes => "content";
report_diffs   => "true";
update_hashes  => "no";
}

##--------------------------------------------------------------
## Packages promises
##--------------------------------------------------------------

body package_method zypper

{
package_changes => "bulk";

package_list_command => "/bin/rpm -qa --queryformat \"i | repos | %{name} | %{version}-%{release} | %{arch}\n\"";

# set it to "0" to avoid caching of list during upgrade
package_list_update_command => "/usr/bin/zypper list-updates";
package_list_update_ifelapsed => "240";

package_patch_list_command => "/usr/bin/zypper patches";
package_installed_regex => "i.*";
package_list_name_regex    => "[^|]+\|[^|]+\|\s+([^\s]+).*";
package_list_version_regex => "[^|]+\|[^|]+\|[^|]+\|\s+([^\s]+).*";
package_list_arch_regex    => "[^|]+\|[^|]+\|[^|]+\|[^|]+\|\s+([^\s]+).*";

package_patch_installed_regex => ".*Installed.*|.*Not Applicable.*";
package_patch_name_regex    => "[^|]+\|\s+([^\s]+).*";
package_patch_version_regex => "[^|]+\|[^|]+\|\s+([^\s]+).*";

package_name_convention => "$(name)";
package_add_command => "/usr/bin/zypper --non-interactive install";
package_delete_command => "/usr/bin/zypper --non-interactive remove --force-resolution";
package_update_command => "/usr/bin/zypper --non-interactive update";
package_patch_command => "/usr/bin/zypper --non-interactive patch$"; # $ means no args
package_verify_command => "/usr/bin/zypper --non-interactive verify$";
}

##

bundle common debian_knowledge
{
  vars:
      "apt_prefix" string => "/usr/bin/env DEBIAN_FRONTEND=noninteractive LC_ALL=C PATH=/bin:/sbin/:/usr/bin:/usr/sbin";
      "call_dpkg" string => "$(apt_prefix) $(paths.path[dpkg])";
      "call_apt_get" string => "$(apt_prefix) $(paths.path[apt_get])";
      "call_aptitude" string => "$(apt_prefix) $(paths.path[aptitude])";
      "dpkg_options" string => "-o Dpkg::Options::=--force-confold -o Dpkg::Options::=--force-confdef";
}

body package_method apt
{
package_changes => "bulk";
package_list_command => "$(debian_knowledge.call_dpkg) -l";
package_list_name_regex    => ".i\s+([^\s]+).*";
package_list_version_regex => ".i\s+[^\s]+\s+([^\s]+).*";
package_installed_regex => ".i.*"; # packages that have been uninstalled may be listed
package_name_convention => "$(name)";

# set it to "0" to avoid caching of list during upgrade
package_list_update_ifelapsed => "240";

have_aptitude::
   package_add_command => "$(debian_knowledge.call_aptitude) $(debian_knowledge.dpkg_options) --assume-yes install";
   package_list_update_command => "/usr/bin/aptitude update";
   package_delete_command => "$(debian_knowledge.call_aptitude) $(debian_knowledge.dpkg_options) --assume-yes -q remove";
   package_update_command =>  "$(debian_knowledge.call_aptitude) $(debian_knowledge.dpkg_options) --assume-yes install";
   package_patch_command =>  "$(debian_knowledge.call_aptitude) $(debian_knowledge.dpkg_options) --assume-yes install";
   package_verify_command =>  "/usr/bin/aptitude show";
   package_noverify_regex => "(State: not installed|E: Unable to locate package .*)";

   package_patch_list_command => "/usr/bin/aptitude --assume-yes --simulate --verbose full-upgrade";
   package_patch_name_regex => "^Inst\s+(\S+)\s+.*";
   package_patch_version_regex => "^Inst\s+\S+\s+\[?\(?([^\],\s]+).*";

!have_aptitude::
   package_add_command => "$(debian_knowledge.call_apt_get) $(debian_knowledge.dpkg_options) --yes install";
   package_list_update_command => "$(debian_knowledge.call_apt_get) update";
   package_delete_command => "$(debian_knowledge.call_apt_get) $(debian_knowledge.dpkg_options) --yes -q remove";
   package_update_command =>  "$(debian_knowledge.call_apt_get) $(debian_knowledge.dpkg_options) --yes install";
   package_patch_command =>  "$(debian_knowledge.call_apt_get) $(debian_knowledge.dpkg_options) --yes install";
   package_verify_command => "$(debian_knowledge.call_dpkg) -s";
   package_noverify_returncode => "1";

   package_patch_list_command => "$(debian_knowledge.call_apt_get) --just-print dist-upgrade";
   package_patch_name_regex => "^Inst\s+(\S+)\s+.*";
   package_patch_version_regex => "^Inst\s+\S+\s+\[?\(?([^\],\s]+).*";

}

# Ignore aptitude because:
#  1) aptitude will remove "unneeded" packages unexpectly
#  2) aptitude return codes are useless
#  3) aptitude is a high level interface
#  4) aptitude provides little benefit
#  5) have_aptitude is a hard class and thus cannot be unset
body package_method apt_get
{
      package_changes => "bulk";
      package_list_command => "$(debian_knowledge.call_dpkg) -l";
      package_list_name_regex    => ".i\s+([^\s]+).*";
      package_list_version_regex => ".i\s+[^\s]+\s+([^\s]+).*";
      package_installed_regex => ".i.*"; # packages that have been uninstalled may be listed
      package_name_convention => "$(name)";

      # set it to "0" to avoid caching of list during upgrade
      package_list_update_ifelapsed => "240";

      # Target a specific release, such as backports
      package_add_command => "$(debian_knowledge.call_apt_get) $(debian_knowledge.dpkg_options) --yes install";
      package_list_update_command => "$(debian_knowledge.call_apt_get) update";
      package_delete_command => "$(debian_knowledge.call_apt_get) $(debian_knowledge.dpkg_options) --yes -q remove";
      package_update_command =>  "$(debian_knowledge.call_apt_get) $(debian_knowledge.dpkg_options) --yes install";
      package_patch_command =>  "$(debian_knowledge.call_apt_get) $(debian_knowledge.dpkg_options) --yes install";
      package_verify_command => "$(debian_knowledge.call_dpkg) -s";
      package_noverify_returncode => "1";

      package_patch_list_command => "$(debian_knowledge.call_apt_get) --just-print dist-upgrade";
      package_patch_name_regex => "^Inst\s+(\S+)\s+.*";
      package_patch_version_regex => "^Inst\s+\S+\s+\[?\(?([^\],\s]+).*";
}

body package_method apt_get_release(release)
{
      package_changes => "bulk";
      package_list_command => "$(debian_knowledge.call_dpkg) -l";
      package_list_name_regex    => ".i\s+([^\s]+).*";
      package_list_version_regex => ".i\s+[^\s]+\s+([^\s]+).*";
      package_installed_regex => ".i.*"; # packages that have been uninstalled may be listed
      package_name_convention => "$(name)";

      # set it to "0" to avoid caching of list during upgrade
      package_list_update_ifelapsed => "240";

      # Target a specific release, such as backports
      package_add_command => "$(debian_knowledge.call_apt_get) $(debian_knowledge.dpkg_options) --yes --target-release $(release) install";
      package_list_update_command => "$(debian_knowledge.call_apt_get) update";
      package_delete_command => "$(debian_knowledge.call_apt_get) $(debian_knowledge.dpkg_options) --yes -q remove";
      package_update_command =>  "$(debian_knowledge.call_apt_get) $(debian_knowledge.dpkg_options) --yes --target-release $(release) install";
      package_patch_command =>  "$(debian_knowledge.call_apt_get) $(debian_knowledge.dpkg_options) --yes --target-release $(release) install";
      package_verify_command => "$(debian_knowledge.call_dpkg) -s";
      package_noverify_returncode => "1";

      package_patch_list_command => "$(debian_knowledge.call_apt_get) --just-print dist-upgrade";
      package_patch_name_regex => "^Inst\s+(\S+)\s+.*";
      package_patch_version_regex => "^Inst\s+\S+\s+\[?\(?([^\],\s]+).*";
}

##

body package_method dpkg_version(repo)
{
package_changes => "individual";
package_list_command => "$(debian_knowledge.call_dpkg) -l";

# set it to "0" to avoid caching of list during upgrade
package_list_update_command => "$(debian_knowledge.call_apt_get) update";
package_list_update_ifelapsed => "240";

package_list_name_regex    => ".i\s+([^\s]+).*";
package_list_version_regex => ".i\s+[^\s]+\s+([^\s]+).*";

package_installed_regex => ".i.*"; # packages that have been uninstalled may be listed

package_file_repositories => { "$(repo)" };

debian.x86_64::
   package_name_convention => "$(name)_$(version)_amd64.deb";

debian.i686::
   package_name_convention => "$(name)_$(version)_i386.deb";

have_aptitude::
   package_patch_list_command => "/usr/bin/aptitude --assume-yes --simulate --verbose full-upgrade";
   package_patch_name_regex => "^Inst\s+(\S+)\s+.*";
   package_patch_version_regex => "^Inst\s+\S+\s+\[?\(?([^\],\s]+).*";
!have_aptitude::
   package_patch_list_command => "$(debian_knowledge.call_apt_get) --just-print dist-upgrade";
   package_patch_name_regex => "^Inst\s+(\S+)\s+.*";
   package_patch_version_regex => "^Inst\s+\S+\s+\[?\(?([^\],\s]+).*";

debian::
   package_add_command => "$(debian_knowledge.call_dpkg) --install";
   package_delete_command => "$(debian_knowledge.call_dpkg) --purge";
   package_update_command =>  "$(debian_knowledge.call_dpkg) --install";
   package_patch_command =>  "$(debian_knowledge.call_dpkg) --install";
}

##

body package_method rpm_version(repo)
{
package_changes => "individual";

package_list_command => "/bin/rpm -qa --queryformat \"i | repos | %{name} | %{version}-%{release} | %{arch}\n\"";

# set it to "0" to avoid caching of list during upgrade
package_list_update_command => "/usr/bin/yum --quiet check-update";
package_list_update_ifelapsed => "240";

package_list_name_regex    => "[^|]+\|[^|]+\|\s+([^\s|]+).*";
package_list_version_regex => "[^|]+\|[^|]+\|[^|]+\|\s+([^\s|]+).*";
package_list_arch_regex    => "[^|]+\|[^|]+\|[^|]+\|[^|]+\|\s+([^\s]+).*";

package_installed_regex => "i.*";

package_file_repositories => { "$(repo)" };

package_name_convention => "$(name)-$(version).$(arch).rpm";

package_add_command => "/bin/rpm -ivh ";
package_update_command => "/bin/rpm -Uvh ";
package_patch_command => "/bin/rpm -Uvh ";
package_delete_command => "/bin/rpm -e --nodeps";
package_verify_command => "/bin/rpm -V";
package_noverify_regex => ".*[^\s].*";
}

##

body package_method windows_feature
{
package_changes => "individual";

package_name_convention   => "$(name)";
package_delete_convention => "$(name)";

package_installed_regex => ".*";
package_list_name_regex => "(.*)";
package_list_version_regex => "(.*)";  # FIXME: the listing does not give version, so takes name for version too now

package_add_command    => "$(sys.winsysdir)\\WindowsPowerShell\\v1.0\\powershell.exe -Command \"Import-Module ServerManager; Add-WindowsFeature -Name\"";
package_delete_command => "$(sys.winsysdir)\\WindowsPowerShell\\v1.0\\powershell.exe -Command \"Import-Module ServerManager; Remove-WindowsFeature -confirm:$false -Name\"";
package_list_command   => "$(sys.winsysdir)\\WindowsPowerShell\\v1.0\\powershell.exe -Command \"Import-Module ServerManager; Get-WindowsFeature | where {$_.installed -eq $True} |foreach {$_.Name}\"";
}

##

body package_method msi_implicit(repo)
# Use whole file name as promiser, e.g. "7-Zip-4.50-x86_64.msi",
# the name, version and arch is then deduced from the promiser
{
package_changes => "individual";
package_file_repositories => { "$(repo)" };

package_installed_regex => ".*";

package_name_convention => "$(name)-$(version)-$(arch).msi";
package_delete_convention => "$(firstrepo)$(name)-$(version)-$(arch).msi";

package_name_regex => "^(\S+)-(\d+\.?)+";
package_version_regex => "^\S+-((\d+\.?)+)";
package_arch_regex => "^\S+-[\d\.]+-(.*).msi";

package_add_command => "\"$(sys.winsysdir)\msiexec.exe\" /qn /i";
package_update_command => "\"$(sys.winsysdir)\msiexec.exe\" /qn /i";
package_delete_command => "\"$(sys.winsysdir)\msiexec.exe\" /qn /x";
}

##

body package_method msi_explicit(repo)
# use software name as promiser, e.g. "7-Zip", and explicitly
# specify any package_version and package_arch
{
package_changes => "individual";
package_file_repositories => { "$(repo)" };

package_installed_regex => ".*";

package_name_convention => "$(name)-$(version)-$(arch).msi";
package_delete_convention => "$(firstrepo)$(name)-$(version)-$(arch).msi";

package_add_command => "\"$(sys.winsysdir)\msiexec.exe\" /qn /i";
package_update_command => "\"$(sys.winsysdir)\msiexec.exe\" /qn /i";
package_delete_command => "\"$(sys.winsysdir)\msiexec.exe\" /qn /x";
}

##

# This package_method uses yum to install, update and list packages.
#
# It will use "rpm -e" to remove packages. Please note that if several packages
# with the same name but varying versions or architectures are installed,
# "rpm -e" will return an error and not delete any of them.
body package_method yum
{
      package_changes => "bulk";
      package_list_command => "/usr/bin/yum --quiet list installed";
      package_patch_list_command => "/usr/bin/yum --quiet check-update";

      # Remember to escape special characters like |

      package_list_name_regex    => "([^.]+).*";
      package_list_version_regex => "[^\s]\s+([^\s]+).*";
      package_list_arch_regex    => "[^.]+\.([^\s]+).*";

      package_installed_regex => ".*(installed|\s+@).*";
      package_name_convention => "$(name)-$(version).$(arch)";

      # just give the package name to rpm to delete, otherwise it gets "name.*" (from package_name_convention above)
      package_delete_convention => "$(name)";

      # set it to "0" to avoid caching of list during upgrade
      package_list_update_command => "/usr/bin/yum --quiet check-update";
      package_list_update_ifelapsed => "240";

      package_patch_installed_regex => "^\s.*";
      package_patch_name_regex    => "([^.]+).*";
      package_patch_version_regex => "[^\s]\s+([^\s]+).*";
      package_patch_arch_regex    => "[^.]+\.([^\s]+).*";

      package_add_command => "/usr/bin/yum -y install";
      package_update_command => "/usr/bin/yum -y update";
      package_patch_command => "/usr/bin/yum -y update";
      package_delete_command => "/bin/rpm -e --nodeps";
      package_verify_command => "/bin/rpm -V";
}

##

body package_method yum_rpm

# Contributed by Trond Hasle Amundsen
# More efficient package method for RPM-based systems - uses rpm
# instead of yum to list installed packages
#
# It will use "rpm -e" to remove packages. Please note that if several packages
# with the same name but varying versions or architectures are installed,
# "rpm -e" will return an error and not delete any of them.

{
  package_changes => "bulk";
  package_list_command => "/bin/rpm -qa --qf '%{name}.%{arch} %{version}-%{release}\n'";
  package_patch_list_command => "/usr/bin/yum --quiet check-update";

  package_list_name_regex    => "([^.]+).*";
  package_list_version_regex => "[^\s]\s+([^\s]+).*";
  package_list_arch_regex    => "[^.]+\.([^\s]+).*";

  package_installed_regex => ".*";
  package_name_convention => "$(name)-$(version).$(arch)";

  # just give the package name to rpm to delete, otherwise it gets "name-*.*" (from package_name_convention above)
  package_delete_convention => "$(name)";

  # set it to "0" to avoid caching of list during upgrade
  package_list_update_command => "/usr/bin/yum --quiet check-update";
  package_list_update_ifelapsed => "240";

  package_patch_installed_regex => "^\s.*";
  package_patch_name_regex    => "([^.]+).*";
  package_patch_version_regex => "[^\s]\s+([^\s]+).*";
  package_patch_arch_regex    => "[^.]+\.([^\s]+).*";

  package_add_command    => "/usr/bin/yum -y install";
  package_update_command => "/usr/bin/yum -y update";
  package_patch_command  => "/usr/bin/yum -y update";
  package_delete_command => "/bin/rpm -e --nodeps";
  package_verify_command => "/bin/rpm -V";
}

##

body package_method yum_rpm_enable_repo(repoid)

# based on yum_rpm with addition to enable a repository for the install
# Sometimes repositories are configured but disabled by default. For example
# this pacakge_method could be used when installing a package that exists in
# the EPEL, which normally you do not want to install packages from.
{
  package_changes => "bulk";
  package_list_command => "/bin/rpm -qa --qf '%{name} %{version}-%{release} %{arch}\n'";
  package_patch_list_command => "/usr/bin/yum --quiet check-update";

  package_list_name_regex    => "^(\S+?)\s\S+?\s\S+$";
  package_list_version_regex => "^\S+?\s(\S+?)\s\S+$";
  package_list_arch_regex    => "^\S+?\s\S+?\s(\S+)$";

  package_installed_regex => ".*";
  package_name_convention => "$(name)";

  # set it to "0" to avoid caching of list during upgrade
  package_list_update_command => "/usr/bin/yum --quiet check-update";
  package_list_update_ifelapsed => "240";

  package_patch_installed_regex => "^\s.*";
  package_patch_name_regex    => "([^.]+).*";
  package_patch_version_regex => "[^\s]\s+([^\s]+).*";
  package_patch_arch_regex    => "[^.]+\.([^\s]+).*";

  package_add_command    => "/usr/bin/yum --enablerepo=$(repoid) -y install";
  package_update_command => "/usr/bin/yum --enablerepo=$(repoid) -y update";
  package_patch_command => "/usr/bin/yum -y update";
  package_delete_command => "/bin/rpm -e --nodeps --allmatches";
  package_verify_command => "/bin/rpm -V";
}

##

body package_method yum_group

# Makes use of the "groups of packages" feature of Yum possible. (yum groupinstall, groupremove)
#
# Groups must be specified by their groupids, available through yum grouplist -v (between parentheses)
# $ yum grouplist -v|grep Networking|head -n 1
#   Networking Tools (network-tools)       <--- network-tools is the groupid
#
# Policies examples:
#
# -Install "web-server" group:
# ----------------------------
#
# packages:
#   "web-server"
#     package_policy   =>  "add",
#     package_method   =>  yum_group;
#
# -Remove "debugging" and "php" groups:
# -------------------------------------
#
# vars:
#   "groups"  slist  => { "debugging", "php" };
#
# packages:
#   "$(groups)"
#      package_policy   =>   "delete",
#      package_method   =>   yum_group;
#

{ 
  package_add_command             =>  "/usr/bin/yum groupinstall -y";
  package_changes                 =>  "bulk";
  package_delete_command          =>  "/usr/bin/yum groupremove -y";
  package_delete_convention       =>  "$(name)";
  package_installed_regex         =>  "^i.*";

  # Generate a dpkg -l like listing, "i" means installed, "a" available, and a dummy version 1
  package_list_command            =>
                                      "/usr/bin/yum grouplist -v|awk '$0 ~ /^Done$/ {next} {sub(/.*\(/, \"\");sub(/\).*/, \"\")} /Available/ {h=\"a\";next} /Installed/ {h=\"i\";next} h==\"i\" || h==\"a\" {print h\" \"$0\" 1\"}'";

  package_list_name_regex         =>  "a|i ([^\s]+) 1";
  package_list_update_command     =>  "/usr/bin/yum --quiet check-update";
  package_list_update_ifelapsed   =>  "240";
  package_list_version_regex      =>  "(1)";
  package_name_convention         =>  "$(name)";
  package_name_regex              =>  "(.*)";
  package_noverify_returncode     =>  "0";
  package_update_command          =>  "/usr/bin/yum groupupdate";

  # grep -x to only get full line matching
  package_verify_command          => "/usr/bin/yum grouplist -v|awk '$0 ~ /^Done$/ {next} {sub(/.*\(/, \"\");sub(/\).*/, \"\")} /Available/ {h=\"a\";next} /Installed/ {h=\"i\";next} h==\"i\"|grep -qx";
}

##

body package_method rpm_filebased(path)

# Contributed by Aleksey Tsalolikhin. Written on 29-Feb-2012.
# Based on yum_rpm body in COPBL by Trond Hasle Amundsen.
# Purpose: install packages from local filesystem-based package repository.
# Note: Specify the path to the local package repository in the argument.

# Example of how to use it:
#
# {{{
# packages:
# "epel-release"
# package_policy => "add",
# package_version => "5-4",
# package_architectures => { "noarch" },
# package_method => rpm_filebased("/repo/RPMs");
# }}}

{
  package_file_repositories => { "$(path)" };
  # the above is an addition to Trond's yum_rpm body

  package_add_command => "/bin/rpm -ihv ";
  # The above is a change from Trond's yum_rpm body, this makes the commands rpm only.
  # The reason I changed the install command from yum to rpm is yum will be default
  # refuse to install the epel-release RPM as it does not have the EPEL GPG key,
  # but rpm goes ahead and installs the epel-release RPM and the EPEL GPG key.

  package_name_convention => "$(name)-$(version).$(arch).rpm";
  # The above is a change from Tron's yum_rpm body. When package_file_repositories is in play,
  # package_name_convention has to match the file name, not the package name, per the
  # CFEngine 3 Reference Manual

  # set it to "0" to avoid caching of list during upgrade
  package_list_update_command => "/usr/bin/yum --quiet check-update";
  package_list_update_ifelapsed => "240";

  # The rest is unchanged from Trond's yum_rpm body
  package_changes => "bulk";
  package_list_command => "/bin/rpm -qa --qf '%{name} %{version}-%{release} %{arch}\n'";

  package_list_name_regex => "^(\S+?)\s\S+?\s\S+$";
  package_list_version_regex => "^\S+?\s(\S+?)\s\S+$";
  package_list_arch_regex => "^\S+?\s\S+?\s(\S+)$";

  package_installed_regex => ".*";


  package_delete_command => "/bin/rpm -e --allmatches";
  package_verify_command => "/bin/rpm -V";
}

##

# OpenSolaris based systems (Solaris 11, Illumos, etc) use the much better
# Image Package System.
#
# A note about Solaris 11.1 versioning format:
#
# $ pkg list -v --no-refresh zsh
# FMRI                                                                         IFO
# pkg://solaris/shell/zsh@4.3.17,5.11-0.175.1.0.0.24.0:20120904T174236Z        i--
# name--------- |<----->| |/________________________\|
# version---------------- |\                        /|
#
# Notice that the publisher and timestamp aren't used. And that the package
# version then must have the commas replaced by underscores.
#
# Thus,
#     4.3.17,5.11-0.175.1.0.0.24.0
# Becomes:
#     4.3.17_5.11-0.175.1.0.0.24.0
#
# Therefore, a properly formatted package promise looks like this:
#    "shell/zsh"
#      package_policy  => "addupdate",
#      package_method  => ips,
#      package_select  => ">=",
#      package_version => "4.3.17_5.11-0.175.1.0.0.24.0";

body package_method ips
{
  package_changes => "bulk";
  package_list_command => "/usr/bin/pkg list -v --no-refresh";
  package_list_name_regex    => "pkg://.+?(?<=/)([^\s]+)@.*$";
  package_list_version_regex => "[^\s]+@([^\s]+):.*";
  package_installed_regex => ".*(i..)"; # all reported are installed

  # set it to "0" to avoid caching of list during upgrade
  package_list_update_command => "/usr/bin/pkg refresh --full";
  package_list_update_ifelapsed => "240";

  package_add_command => "/usr/bin/pkg install --accept ";
  package_delete_command => "/usr/bin/pkg uninstall";
  package_update_command =>  "/usr/bin/pkg install --accept";
  package_patch_command =>  "/usr/bin/pkg install --accept";
  package_verify_command =>  "/usr/bin/pkg list -a -v --no-refresh";
  package_noverify_regex => "(.*---|pkg list: no packages matching .* installed)";
}

##

# SmartOS (solaris 10 fork by Joyent) uses pkgin

body package_method smartos
{
  package_changes => "bulk";
  package_list_command => "/opt/local/bin/pkgin list";
  package_list_name_regex    => "(.*)\-[0-9]+.*";
  package_list_version_regex => ".*\-([0-9][^\s]+).*";

  package_installed_regex => ".*"; # all reported are installed

  package_list_update_command => "/opt/local/bin/pkgin -y update";
  package_list_update_ifelapsed => "240";

  package_add_command => "/opt/local/bin/pkgin -y install";

  package_delete_command => "/opt/local/bin/pkgin -y remove";
  package_update_command =>  "/opt/local/bin/pkgin upgrade";
}

# OpenCSW (Solaris software packages)

body package_method opencsw
{
  package_changes => "bulk";
  package_list_command => "/opt/csw/bin/pkgutil -c";
  package_list_name_regex    => "CSW(.*?)\s.*";
  package_list_version_regex => ".*?\s+(.*),.*";

  package_installed_regex => ".*"; # all reported are installed

  package_list_update_command => "/opt/csw/bin/pkgutil -U";
  package_list_update_ifelapsed => "240";

  package_add_command => "/opt/csw/bin/pkgutil -yi";

  package_delete_command => "/opt/csw/bin/pkgutil -yr";
  package_update_command =>  "/opt/csw/bin/pkgutil -yu";
}

# The older solaris package system is poorly designed, with too many different
# names to track. See the example in tests/units/unit_package_solaris.cf
# to see how to use this

body package_method solaris (pkgname, spoolfile, adminfile)
{
package_changes => "individual";
package_list_command => "/usr/bin/pkginfo -l";
package_multiline_start    =>  "\s*PKGINST:\s+[^\s]+.*";
package_list_name_regex    => "\s*PKGINST:\s+([^\s]+).*";
package_list_version_regex => "\s*VERSION:\s+([^\s]+).*";
package_list_arch_regex    => "\s*ARCH:\s+([^\s]+)";
package_installed_regex => "\s*STATUS:\s*(completely|partially)\s+installed.*";
package_name_convention => "$(name)";
package_add_command => "/usr/sbin/pkgadd -n -a /tmp/$(adminfile) -d /tmp/$(spoolfile)";
package_delete_command => "/usr/sbin/pkgrm -n -a /tmp/$(adminfile)";
}

##

#
# The following bundle is part of a package setup for solaris, see unit examples
#

bundle edit_line create_solaris_admin_file
{
insert_lines:

  "mail=
instance=unique
partial=nocheck
runlevel=nocheck
idepend=nocheck
rdepend=nocheck
space=nocheck
setuid=nocheck
conflict=nocheck
action=nocheck
networktimeout=60
networkretries=3
authentication=quit
keystore=/var/sadm/security
proxy=
basedir=default"
      comment => "Insert contents of Solaris admin file (automatically install packages)";
}

##

body package_method freebsd
{
 package_changes => "individual";

 # Could use rpm for this
 package_list_command => "/usr/sbin/pkg_info";

 # Remember to escape special characters like |

 package_list_name_regex    => "([^\s]+)-.*";
 package_list_version_regex => "[^\s]+-([^\s]+).*";

 package_name_regex    => "([^\s]+)-.*";
 package_version_regex => "[^\s]+-([^\s]+).*";

 package_installed_regex => ".*";

 package_name_convention => "$(name)-$(version)";


 package_add_command => "/usr/sbin/pkg_add -r";
 package_delete_command => "/usr/sbin/pkg_delete";
}

body package_method freebsd_portmaster
{
 package_changes => "individual";

 package_list_command => "/usr/sbin/pkg_info";

 package_list_name_regex    => "([^\s]+)-.*";
 package_list_version_regex => "[^\s]+-([^\s]+).*";

 package_installed_regex => ".*";

 package_name_convention => "$(name)";
 package_delete_convention => "$(name)-$(version)";

 package_file_repositories => {
  "/usr/ports/accessibility/",
  "/usr/port/arabic/",
  "/usr/ports/archivers/",
  "/usr/ports/astro/",
  "/usr/ports/audio/",
  "/usr/ports/benchmarks/",
  "/usr/ports/biology/",
  "/usr/ports/cad/",
  "/usr/ports/chinese/",
  "/usr/ports/comms/",
  "/usr/ports/converters/",
  "/usr/ports/databases/",
  "/usr/ports/deskutils/",
  "/usr/ports/devel/",
  "/usr/ports/dns/",
  "/usr/ports/editors/",
  "/usr/ports/emulators/",
  "/usr/ports/finance/",
  "/usr/ports/french/",
  "/usr/ports/ftp/",
  "/usr/ports/games/",
  "/usr/ports/german/",
  "/usr/ports/graphics/",
  "/usr/ports/hebrew/",
  "/usr/ports/hungarian/",
  "/usr/ports/irc/",
  "/usr/ports/japanese/",
  "/usr/ports/java/",
  "/usr/ports/korean/",
  "/usr/ports/lang/",
  "/usr/ports/mail/",
  "/usr/ports/math/",
  "/usr/ports/mbone/",
  "/usr/ports/misc/",
  "/usr/ports/multimedia/",
  "/usr/ports/net/",
  "/usr/ports/net-im/",
  "/usr/ports/net-mgmt/",
  "/usr/ports/net-p2p/",
  "/usr/ports/news/",
  "/usr/ports/packages/",
  "/usr/ports/palm/",
  "/usr/ports/polish/",
  "/usr/ports/ports-mgmt/",
  "/usr/ports/portuguese/",
  "/usr/ports/print/",
  "/usr/ports/russian/",
  "/usr/ports/science/",
  "/usr/ports/security/",
  "/usr/ports/shells/",
  "/usr/ports/sysutils/",
  "/usr/ports/textproc/",
  "/usr/ports/ukrainian/",
  "/usr/ports/vietnamese/",
  "/usr/ports/www/",
  "/usr/ports/x11/",
  "/usr/ports/x11-clocks/",
  "/usr/ports/x11-drivers/",
  "/usr/ports/x11-fm/",
  "/usr/ports/x11-fonts/",
  "/usr/ports/x11-servers/",
  "/usr/ports/x11-themes/",
  "/usr/ports/x11-toolkits/",
  "/usr/ports/x11-wm/",
 };

 package_add_command => "/usr/local/sbin/portmaster -D -G --no-confirm";
 package_update_command => "/usr/local/sbin/portmaster -D -G --no-confirm";
 package_delete_command => "/usr/local/sbin/portmaster --no-confirm -e";
}

##

body package_method alpinelinux
{
 package_changes => "bulk";
 package_list_command => "/sbin/apk info -v";
 package_list_name_regex    => "([^\s]+)-.*";
 package_list_version_regex => "[^\s]+-([^\s]+).*";
 package_name_regex    => ".*";
 package_installed_regex => ".*";
 package_name_convention => "$(name)";
 package_add_command => "/sbin/apk add";
 package_delete_command => "/sbin/apk del";
}

##

body package_method emerge
{
 package_changes => "individual";
 package_list_command => "/bin/sh -c '/bin/ls -d /var/db/pkg/*/* | cut -c 13-'";
 package_list_name_regex => ".*/([^\s]+)-\d.*";
 package_list_version_regex => ".*/[^\s]+-(\d.*)";
 package_installed_regex => ".*";                          # all reported are installed
 package_name_convention => "$(name)";
 package_list_update_command => "/bin/true";               # I prefer manual syncing
 #package_list_update_command => "/usr/bin/emerge --sync"; # if you like automatic
 package_list_update_ifelapsed => "240";                   # should happen every 4 hours

 package_add_command => "/usr/bin/emerge -q --quiet-build";
 package_delete_command => "/usr/bin/emerge --depclean";
 package_update_command => "/usr/bin/emerge --update";
 package_patch_command => "/usr/bin/emerge --update";
 package_verify_command => "/usr/bin/emerge -s";
 package_noverify_regex => ".*(Not Installed|Applications found : 0).*";
}

##

body package_method pacman

{
package_changes => "bulk";

package_list_command => "/usr/bin/pacman -Q";

# set it to "0" to avoid caching of list during upgrade
package_list_update_ifelapsed => "240";

package_list_name_regex    => "(.*)\s+.*";
package_list_version_regex => ".*\s+(.*)";
package_installed_regex => ".*";

package_name_convention => "$(name)";
package_add_command => "/usr/bin/pacman -S --noconfirm --noprogressbar --needed";
package_delete_command => "/usr/bin/pacman -Rs --noconfirm";
package_update_command => "/usr/bin/pacman -S --noconfirm --noprogressbar --needed";
}

##

 # Single bundle for all the similar managers simplifies promises

body package_method generic
{
SuSE::
 package_changes => "bulk";
 package_list_command => "/bin/rpm -qa --queryformat \"i | repos | %{name} | %{version}-%{release} | %{arch}\n\"";
 # set it to "0" to avoid caching of list during upgrade
 package_list_update_command => "/usr/bin/zypper list-updates";
 package_list_update_ifelapsed => "0";
 package_patch_list_command => "/usr/bin/zypper patches";
 package_installed_regex => "i.*";
 package_list_name_regex    => "[^|]+\|[^|]+\|\s+([^\s]+).*";
 package_list_version_regex => "[^|]+\|[^|]+\|[^|]+\|\s+([^\s]+).*";
 package_list_arch_regex    => "[^|]+\|[^|]+\|[^|]+\|[^|]+\|\s+([^\s]+).*";
 package_patch_installed_regex => ".*Installed.*|.*Not Applicable.*";
 package_patch_name_regex    => "[^|]+\|\s+([^\s]+).*";
 package_patch_version_regex => "[^|]+\|[^|]+\|\s+([^\s]+).*";
 package_name_convention => "$(name)";
 package_add_command => "/usr/bin/zypper --non-interactive install";
 package_delete_command => "/usr/bin/zypper --non-interactive remove --force-resolution";
 package_update_command => "/usr/bin/zypper --non-interactive update";
 package_patch_command => "/usr/bin/zypper --non-interactive patch$"; # $ means no args
 package_verify_command => "/usr/bin/zypper --non-interactive verify$";

redhat::
 package_changes => "bulk";
 package_list_command => "/bin/rpm -qa --qf '%{name} %{version}-%{release} %{arch}\n'";
 package_patch_list_command => "/usr/bin/yum --quiet check-update";
 package_list_name_regex    => "^(\S+?)\s\S+?\s\S+$";
 package_list_version_regex => "^\S+?\s(\S+?)\s\S+$";
 package_list_arch_regex    => "^\S+?\s\S+?\s(\S+)$";
 package_installed_regex => ".*";
 package_name_convention => "$(name)";
 package_list_update_command => "/usr/bin/yum --quiet check-update";
 package_list_update_ifelapsed => "0";     # sometimes, caching is pretty disturbing
 package_patch_installed_regex => "^\s.*";
 package_patch_name_regex    => "([^.]+).*";
 package_patch_version_regex => "[^\s]\s+([^\s]+).*";
 package_patch_arch_regex    => "[^.]+\.([^\s]+).*";
 package_add_command    => "/usr/bin/yum -y install";
 package_update_command => "/usr/bin/yum -y update";
 package_patch_command => "/usr/bin/yum -y update";
 package_delete_command => "/bin/rpm -e --nodeps --allmatches";
 package_verify_command => "/bin/rpm -V";

# package_changes => "bulk";
# package_list_command => "/usr/bin/yum list installed";
# package_patch_list_command => "/usr/bin/yum check-update";
# package_list_name_regex    => "([^.]+).*";
# package_list_version_regex => "[^\s]\s+([^\s]+).*";
# package_list_arch_regex    => "[^.]+\.([^\s]+).*";
# package_installed_regex => ".*(installed|\s+@).*";
# package_name_convention => "$(name).$(arch)";
# package_list_update_ifelapsed => "240";
# package_patch_installed_regex => "^\s.*";
# package_patch_name_regex    => "([^.]+).*";
# package_patch_version_regex => "[^\s]\s+([^\s]+).*";
# package_patch_arch_regex    => "[^.]+\.([^\s]+).*";
# package_add_command => "/usr/bin/yum -y install";
# package_delete_command => "/bin/rpm -e --nodeps";
# package_verify_command => "/bin/rpm -V";

debian::
 package_changes => "bulk";
 package_list_command => "$(debian_knowledge.call_dpkg) -l";
 package_list_name_regex    => ".i\s+([^\s]+).*";
 package_list_version_regex => ".i\s+[^\s]+\s+([^\s]+).*";
 package_installed_regex => ".i.*"; # packages that have been uninstalled may be listed
 package_name_convention => "$(name)";
 package_list_update_ifelapsed => "240";		# 4 hours

debian.have_aptitude::
   package_add_command => "$(debian_knowledge.call_aptitude) $(debian_knowledge.dpkg_options) --assume-yes install";
   package_list_update_command => "/usr/bin/aptitude update";
   package_delete_command => "$(debian_knowledge.call_aptitude) $(debian_knowledge.dpkg_options) --assume-yes remove";
   package_update_command =>  "$(debian_knowledge.call_aptitude) $(debian_knowledge.dpkg_options) --assume-yes install";
   package_patch_command =>  "$(debian_knowledge.call_aptitude) $(debian_knowledge.dpkg_options) --assume-yes install";
   package_verify_command =>  "/usr/bin/aptitude show";
   package_noverify_regex => "(State: not installed|E: Unable to locate package .*)";

   package_patch_list_command => "/usr/bin/aptitude --assume-yes --simulate --verbose full-upgrade";
   package_patch_name_regex => "^Inst\s+(\S+)\s+.*";
   package_patch_version_regex => "^Inst\s+\S+\s+\[?\(?([^\],\s]+).*";

debian.!have_aptitude::
   package_add_command => "$(debian_knowledge.call_apt_get) $(debian_knowledge.dpkg_options) --yes install";
   package_list_update_command => "$(debian_knowledge.call_apt_get) update";
   package_delete_command => "$(debian_knowledge.call_apt_get) $(debian_knowledge.dpkg_options) --yes remove";
   package_update_command =>  "$(debian_knowledge.call_apt_get) $(debian_knowledge.dpkg_options) --yes install";
   package_patch_command =>  "$(debian_knowledge.call_apt_get) $(debian_knowledge.dpkg_options) --yes install";
   package_verify_command => "$(debian_knowledge.call_dpkg) -s";
   package_noverify_returncode => "1";

   package_patch_list_command => "$(debian_knowledge.call_apt_get) --just-print dist-upgrade";
   package_patch_name_regex => "^Inst\s+(\S+)\s+.*";
   package_patch_version_regex => "^Inst\s+\S+\s+\[?\(?([^\],\s]+).*";

freebsd::
 package_changes => "individual";
 package_list_command => "/usr/sbin/pkg_info";
 package_list_name_regex    => "([^\s]+)-.*";
 package_list_version_regex => "[^\s]+-([^\s]+).*";
 package_name_regex    => "([^\s]+)-.*";
 package_version_regex => "[^\s]+-([^\s]+).*";
 package_installed_regex => ".*";
 package_name_convention => "$(name)-$(version)";
 package_add_command => "/usr/sbin/pkg_add -r";
 package_delete_command => "/usr/sbin/pkg_delete";

alpinelinux::
 package_changes => "bulk";
 package_list_command => "/sbin/apk info -v";
 package_list_name_regex    => "([^\s]+)-.*";
 package_list_version_regex => "[^\s]+-([^\s]+).*";
 package_name_regex    => ".*";
 package_installed_regex => ".*";
 package_name_convention => "$(name)";
 package_add_command => "/sbin/apk add";
 package_delete_command => "/sbin/apk del";

gentoo::
 package_changes => "individual";
 package_list_command => "/bin/sh -c '/bin/ls -d /var/db/pkg/*/* | cut -c 13-'";
 package_list_name_regex => ".*/([^\s]+)-\d.*";
 package_list_version_regex => ".*/[^\s]+-(\d.*)";
 package_installed_regex => ".*";                          # all reported are installed
 package_name_convention => "$(name)";
 package_list_update_command => "/bin/true";               # I prefer manual syncing
 #package_list_update_command => "/usr/bin/emerge --sync"; # if you like automatic
 package_list_update_ifelapsed => "240";                   # should happen every 4 hours

 package_add_command => "/usr/bin/emerge -q --quiet-build";
 package_delete_command => "/usr/bin/emerge --depclean";
 package_update_command => "/usr/bin/emerge --update";
 package_patch_command => "/usr/bin/emerge --update";
 package_verify_command => "/usr/bin/emerge -s";
 package_noverify_regex => ".*(Not Installed|Applications found : 0).*";

archlinux::
 package_changes => "bulk";
 package_list_command => "/usr/bin/pacman -Q";
 package_list_name_regex    => "(.*)\s+.*";
 package_list_version_regex => ".*\s+(.*)";
 package_installed_regex => ".*";
 package_name_convention => "$(name)";
 package_list_update_ifelapsed => "240";
 package_add_command => "/usr/bin/pacman -S --noconfirm --noprogressbar --needed";
 package_delete_command => "/usr/bin/pacman -Rs --noconfirm";
 package_update_command => "/usr/bin/pacman -S --noconfirm --noprogressbar --needed";
}

##

##-------------------------------------------------------
## storage promises
##-------------------------------------------------------

body volume min_free_space(free)
{
check_foreign  => "false";
freespace      => "$(free)";
sensible_size  => "10000";
sensible_count => "2";
}

##

body mount nfs(server,source)
{
mount_type => "nfs";
mount_source => "$(source)";
mount_server => "$(server)";
edit_fstab => "true";
}


##

body mount nfs_p(server,source,perm)
{
mount_type => "nfs";
mount_source => "$(source)";
mount_server => "$(server)";
mount_options => {"$(perm)"};
edit_fstab => "true";
}

##

body mount unmount
{
mount_type => "nfs";
edit_fstab => "true";
unmount => "true";
}

##-------------------------------------------------------
## process promises
##-------------------------------------------------------

body process_select exclude_procs(x)
{
command => "$(x)";
process_result => "!command";
}

##

body process_select days_older_than(d)
{
stime_range    => irange(ago(0,0,"$(d)",0,0,0),now);
process_result => "stime";
}

##

body process_count any_count(cl)

{
match_range => "0,0";
out_of_range_define => { "$(cl)" };
}

##

body process_count check_range(name,lower,upper)
{
match_range => irange("$(lower)","$(upper)");
out_of_range_define => { "$(name)_out_of_range" };
}

##-------------------------------------------------------
## service promises
##-------------------------------------------------------

body service_method bootstart
{
  service_autostart_policy => "boot_time";
  service_dependence_chain => "start_parent_services";
windows::
  service_type => "windows";
}

##

body service_method force_deps
{
  service_dependence_chain => "all_related";
windows::
  service_type => "windows";
}

##

bundle agent standard_services(service,state)
{
 # DATA,

vars:

 any::

  "stakeholders[cfengine3]" slist => { "cfengine_in" };
  "stakeholders[acpid]" slist => { "cpu", "cpu0", "cpu1", "cpu2", "cpu3" };
  "stakeholders[mongod]" slist => { "mongo_in" };
  "stakeholders[postfix]" slist => { "smtp_in" };
  "stakeholders[sendmail]" slist => { "smtp_in" };
  "stakeholders[www]" slist => { "www_in", "wwws_in", "www_alt_in" };
  "stakeholders[ssh]" slist => { "ssh_in" };
  "stakeholders[mysql]" slist => { "mysql_in" };
  "stakeholders[nfs]" slist => { "nfsd_in" };
  "stakeholders[syslog]" slist => { "syslog" };
  "stakeholders[rsyslog]" slist => { "syslog" };
  "stakeholders[tomcat5]" slist => { "www_alt_in" };
  "stakeholders[tomcat6]" slist => { "www_alt_in" };

 linux::

  "startcommand[acpid]"   string => "/etc/init.d/acpid start";
  "restartcommand[acpid]" string => "/etc/init.d/acpid restart";
  "reloadcommand[acpid]"  string => "/etc/init.d/acpid reload";
  "stopcommand[acpid]"    string => "/etc/init.d/acpid stop";
  "pattern[acpid]"        string => ".*acpid.*";

  "startcommand[cfengine3]"   string => "/etc/init.d/cfengine3 start";
  "restartcommand[cfengine3]" string => "/etc/init.d/cfengine3 restart";
  "reloadcommand[cfengine3]"  string => "/etc/init.d/cfengine3 reload";
  "stopcommand[cfengine3]"    string => "/etc/init.d/cfengine3 stop";
  "pattern[cfengine3]"        string => ".*cf-execd.*";

  "startcommand[fancontrol]"   string => "/etc/init.d/fancontrol start";
  "restartcommand[fancontrol]" string => "/etc/init.d/fancontrol restart";
  "reloadcommand[fancontrol]"  string => "/etc/init.d/fancontrol reload";
  "stopcommand[fancontrol]"    string => "/etc/init.d/fancontrol stop";
  "pattern[fancontrol]"        string => ".*fancontrol.*";

  "startcommand[hddtemp]"   string => "/etc/init.d/hddtemp start";
  "restartcommand[hddtemp]" string => "/etc/init.d/hddtemp restart";
  "reloadcommand[hddtemp]"  string => "/etc/init.d/hddtemp reload";
  "stopcommand[hddtemp]"    string => "/etc/init.d/hddtemp stop";
  "pattern[hddtemp]"        string => ".*hddtemp.*";

  "startcommand[irqbalance]"   string => "/etc/init.d/irqbalance start";
  "restartcommand[irqbalance]" string => "/etc/init.d/irqbalance restart";
  "reloadcommand[irqbalance]"  string => "/etc/init.d/irqbalance reload";
  "stopcommand[irqbalance]"    string => "/etc/init.d/irqbalance stop";
  "pattern[irqbalance]"        string => ".*irqbalance.*";

  "startcommand[lm-sensor]"   string => "/etc/init.d/lm-sensor start";
  "restartcommand[lm-sensor]" string => "/etc/init.d/lm-sensor restart";
  "reloadcommand[lm-sensor]"  string => "/etc/init.d/lm-sensor reload";
  "stopcommand[lm-sensor]"    string => "/etc/init.d/lm-sensor stop";
  "pattern[lm-sensor]"        string => ".*psensor.*";

  "startcommand[mongod]"   string => "/etc/init.d/mongod start";
  "restartcommand[mongod]" string => "/etc/init.d/mongod restart";
  "reloadcommand[mongod]"  string => "/etc/init.d/mongod reload";
  "stopcommand[mongod]"    string => "/etc/init.d/mongod stop";
  "pattern[mongod]"        string => ".*mongod.*";

  "startcommand[openvpn]"   string => "/etc/init.d/openvpn start";
  "restartcommand[openvpn]" string => "/etc/init.d/openvpn restart";
  "reloadcommand[openvpn]"  string => "/etc/init.d/openvpn reload";
  "stopcommand[openvpn]"    string => "/etc/init.d/openvpn stop";
  "pattern[openvpn]"        string => ".*openvpn.*";

  "startcommand[postfix]"   string => "/etc/init.d/postfix start";
  "restartcommand[postfix]" string => "/etc/init.d/postfix restart";
  "reloadcommand[postfix]"  string => "/etc/init.d/postfix reload";
  "stopcommand[postfix]"    string => "/etc/init.d/postfix stop";
  "pattern[postfix]"        string => ".*postfix.*";

  "startcommand[rsync]"   string => "/etc/init.d/rsync start";
  "restartcommand[rsync]" string => "/etc/init.d/rsync restart";
  "reloadcommand[rsync]"  string => "/etc/init.d/rsync reload";
  "stopcommand[rsync]"    string => "/etc/init.d/rsync stop";
  "pattern[rsync]"        string => ".*rsync.*";

  "startcommand[rsyslog]"   string => "/etc/init.d/rsyslog start";
  "restartcommand[rsyslog]" string => "/etc/init.d/rsyslog restart";
  "reloadcommand[rsyslog]"  string => "/etc/init.d/rsyslog reload";
  "stopcommand[rsyslog]"    string => "/etc/init.d/rsyslog stop";
  "pattern[rsyslog]"        string => ".*rsyslogd.*";

  "startcommand[sendmail]"   string => "/etc/init.d/sendmail start";
  "restartcommand[sendmail]" string => "/etc/init.d/sendmail restart";
  "reloadcommand[sendmail]"  string => "/etc/init.d/sendmail reload";
  "stopcommand[sendmail]"    string => "/etc/init.d/sendmail stop";
  "pattern[sendmail]"        string => ".*sendmail.*";

  "startcommand[tomcat5]"   string => "/etc/init.d/tomcat5 start";
  "restartcommand[tomcat5]" string => "/etc/init.d/tomcat5 restart";
  "reloadcommand[tomcat5]"  string => "/etc/init.d/tomcat5 reload";
  "stopcommand[tomcat5]"    string => "/etc/init.d/tomcat5 stop";
  "pattern[tomcat5]"        string => ".*tomcat5.*";

  "startcommand[tomcat6]"   string => "/etc/init.d/tomcat6 start";
  "restartcommand[tomcat6]" string => "/etc/init.d/tomcat6 restart";
  "reloadcommand[tomcat6]"  string => "/etc/init.d/tomcat6 reload";
  "stopcommand[tomcat6]"    string => "/etc/init.d/tomcat6 stop";
  "pattern[tomcat6]"        string => ".*tomcat6.*";

  "startcommand[varnish]"   string => "/etc/init.d/varnish start";
  "restartcommand[varnish]" string => "/etc/init.d/varnish restart";
  "reloadcommand[varnish]"  string => "/etc/init.d/varnish reload";
  "stopcommand[varnish]"    string => "/etc/init.d/varnish stop";
  "pattern[varnish]"        string => ".*varnish.*";

  "startcommand[wpa_supplicant]"   string => "/etc/init.d/wpa_supplicant start";
  "restartcommand[wpa_supplicant]" string => "/etc/init.d/wpa_supplicant restart";
  "reloadcommand[wpa_supplicant]"  string => "/etc/init.d/wpa_supplicant reload";
  "stopcommand[wpa_supplicant]"    string => "/etc/init.d/wpa_supplicant stop";
  "pattern[wpa_supplicant]"        string => ".*wpa_supplicant.*";

 SuSE|suse::

  "startcommand[mysql]"   string => "/etc/init.d/mysqld start";
  "restartcommand[mysql]" string => "/etc/init.d/mysqld restart";
  "reloadcommand[mysql]"  string => "/etc/init.d/mysqld reload";
  "stopcommand[mysql]"    string => "/etc/init.d/mysqld stop";
  "pattern[mysql]"        string => ".*mysqld.*";

  "startcommand[www]"   string => "/etc/init.d/apache2 start";
  "restartcommand[www]" string => "/etc/init.d/apache2 restart";
  "reloadcommand[www]"  string => "/etc/init.d/apache2 reload";
  "stopcommand[www]"    string => "/etc/init.d/apache2 stop";
  "pattern[www]"        string => ".*apache2.*";


  "startcommand[ssh]"   string => "/etc/init.d/sshd start";
  "restartcommand[ssh]" string => "/etc/init.d/sshd restart";
  "reloadcommand[ssh]"  string => "/etc/init.d/sshd reload";
  "stopcommand[ssh]"    string => "/etc/init.d/sshd stop";
  "pattern[ssh]"        string => ".*sshd.*";

 redhat::

  "startcommand[anacron]"   string => "/etc/init.d/anacron start";
  "restartcommand[anacron]" string => "/etc/init.d/anacron restart";
  "reloadcommand[anacron]"  string => "/etc/init.d/anacron reload";
  "stopcommand[anacron]"    string => "/etc/init.d/anacron stop";
  "pattern[anacron]"        string => ".*anacron.*";

  "startcommand[atd]"   string => "/etc/init.d/atd start";
  "restartcommand[atd]" string => "/etc/init.d/atd restart";
  "reloadcommand[atd]"  string => "/etc/init.d/atd reload";
  "stopcommand[atd]"    string => "/etc/init.d/atd stop";
  "pattern[atd]"        string => ".*sbin/atd.*";

  "startcommand[auditd]"   string => "/etc/init.d/auditd start";
  "restartcommand[auditd]" string => "/etc/init.d/auditd restart";
  "reloadcommand[auditd]"  string => "/etc/init.d/auditd reload";
  "stopcommand[auditd]"    string => "/etc/init.d/auditd stop";
  "pattern[auditd]"        string => ".*auditd$";

  "startcommand[autofs]"   string => "/etc/init.d/autofs start";
  "restartcommand[autofs]" string => "/etc/init.d/autofs restart";
  "reloadcommand[autofs]"  string => "/etc/init.d/autofs reload";
  "stopcommand[autofs]"    string => "/etc/init.d/autofs stop";
  "pattern[autofs]"        string => ".*automount.*";

  "startcommand[bluetoothd]"   string => "/etc/init.d/bluetooth start";
  "restartcommand[bluetoothd]" string => "/etc/init.d/bluetooth restart";
  "reloadcommand[bluetoothd]"  string => "/etc/init.d/bluetooth reload";
  "stopcommand[bluetoothd]"    string => "/etc/init.d/bluetooth stop";
  "pattern[bluetoothd]"        string => ".*hcid.*";

  "startcommand[capi]"   string => "/etc/init.d/capi start";
  "restartcommand[capi]" string => "/etc/init.d/capi restart";
  "reloadcommand[capi]"  string => "/etc/init.d/capi reload";
  "stopcommand[capi]"    string => "/etc/init.d/capi stop";
  "pattern[capi]"        string => ".*capiinit.*";

  "startcommand[conman]"   string => "/etc/init.d/conman start";
  "restartcommand[conman]" string => "/etc/init.d/conman restart";
  "reloadcommand[conman]"  string => "/etc/init.d/conman reload";
  "stopcommand[conman]"    string => "/etc/init.d/conman stop";
  "pattern[conman]"        string => ".*conmand.*";

  "startcommand[cpuspeed]"   string => "/etc/init.d/cpuspeed start";
  "restartcommand[cpuspeed]" string => "/etc/init.d/cpuspeed restart";
  "reloadcommand[cpuspeed]"  string => "/etc/init.d/cpuspeed reload";
  "stopcommand[cpuspeed]"    string => "/etc/init.d/cpuspeed stop";
  "pattern[cpuspeed]"        string => ".*cpuspeed.*";

  "startcommand[crond]"   string => "/etc/init.d/crond start";
  "restartcommand[crond]" string => "/etc/init.d/crond restart";
  "reloadcommand[crond]"  string => "/etc/init.d/crond reload";
  "stopcommand[crond]"    string => "/etc/init.d/crond stop";
  "pattern[crond]"        string => ".*crond.*";

  "startcommand[dc_client]"   string => "/etc/init.d/dc_client start";
  "restartcommand[dc_client]" string => "/etc/init.d/dc_client restart";
  "reloadcommand[dc_client]"  string => "/etc/init.d/dc_client reload";
  "stopcommand[dc_client]"    string => "/etc/init.d/dc_client stop";
  "pattern[dc_client]"        string => ".*dc_client.*";

  "startcommand[dc_server]"   string => "/etc/init.d/dc_server start";
  "restartcommand[dc_server]" string => "/etc/init.d/dc_server restart";
  "reloadcommand[dc_server]"  string => "/etc/init.d/dc_server reload";
  "stopcommand[dc_server]"    string => "/etc/init.d/dc_server stop";
  "pattern[dc_server]"        string => ".*dc_server.*";

  "startcommand[dnsmasq]"   string => "/etc/init.d/dnsmasq start";
  "restartcommand[dnsmasq]" string => "/etc/init.d/dnsmasq restart";
  "reloadcommand[dnsmasq]"  string => "/etc/init.d/dnsmasq reload";
  "stopcommand[dnsmasq]"    string => "/etc/init.d/dnsmasq stop";
  "pattern[dnsmasq]"        string => ".*dnsmasq.*";

  "startcommand[dund]"   string => "/etc/init.d/dund start";
  "restartcommand[dund]" string => "/etc/init.d/dund restart";
  "reloadcommand[dund]"  string => "/etc/init.d/dund reload";
  "stopcommand[dund]"    string => "/etc/init.d/dund stop";
  "pattern[dund]"        string => ".*dund.*";

  "startcommand[gpm]"   string => "/etc/init.d/gpm start";
  "restartcommand[gpm]" string => "/etc/init.d/gpm restart";
  "reloadcommand[gpm]"  string => "/etc/init.d/gpm reload";
  "stopcommand[gpm]"    string => "/etc/init.d/gpm stop";
  "pattern[gpm]"        string => ".*gpm.*";

  "startcommand[haldaemon]"   string => "/etc/init.d/haldaemon start";
  "restartcommand[haldaemon]" string => "/etc/init.d/haldaemon restart";
  "reloadcommand[haldaemon]"  string => "/etc/init.d/haldaemon reload";
  "stopcommand[haldaemon]"    string => "/etc/init.d/haldaemon stop";
  "pattern[haldaemon]"        string => ".*hald.*";

  "startcommand[hidd]"   string => "/etc/init.d/hidd start";
  "restartcommand[hidd]" string => "/etc/init.d/hidd restart";
  "reloadcommand[hidd]"  string => "/etc/init.d/hidd reload";
  "stopcommand[hidd]"    string => "/etc/init.d/hidd stop";
  "pattern[hidd]"        string => ".*hidd.*";

#  "startcommand[ip6tables]"   string => "/etc/init.d/ip6tables start";
#  "restartcommand[ip6tables]" string => "/etc/init.d/ip6tables restart";
#  "reloadcommand[ip6tables]"  string => "/etc/init.d/ip6tables reload";
#  "stopcommand[ip6tables]"    string => "/etc/init.d/ip6tables stop";
#  "pattern[ip6tables]"        string => ".*ip6tables.*";

#  "startcommand[iptables]"   string => "/etc/init.d/iptables start";
#  "restartcommand[iptables]" string => "/etc/init.d/iptables restart";
#  "reloadcommand[iptables]"  string => "/etc/init.d/iptables reload";
#  "stopcommand[iptables]"    string => "/etc/init.d/iptables stop";
#  "pattern[iptables]"        string => ".*iptables.*";

  "startcommand[irda]"   string => "/etc/init.d/irda start";
  "restartcommand[irda]" string => "/etc/init.d/irda restart";
  "reloadcommand[irda]"  string => "/etc/init.d/irda reload";
  "stopcommand[irda]"    string => "/etc/init.d/irda stop";
  "pattern[irda]"        string => ".*irattach.*";

  "startcommand[iscsid]"   string => "/etc/init.d/iscsid start";
  "restartcommand[iscsid]" string => "/etc/init.d/iscsid restart";
  "reloadcommand[iscsid]"  string => "/etc/init.d/iscsid reload";
  "stopcommand[iscsid]"    string => "/etc/init.d/iscsid stop";
  "pattern[iscsid]"        string => ".*iscsid.*";

  "startcommand[isdn]"   string => "/etc/init.d/isdn start";
  "restartcommand[isdn]" string => "/etc/init.d/isdn restart";
  "reloadcommand[isdn]"  string => "/etc/init.d/isdn reload";
  "stopcommand[isdn]"    string => "/etc/init.d/isdn stop";
  "pattern[isdn]"        string => ".*isdnlog.*";

  "startcommand[lvm2-monitor]"   string => "/etc/init.d/lvm2-monitor start";
  "restartcommand[lvm2-monitor]" string => "/etc/init.d/lvm2-monitor restart";
  "reloadcommand[lvm2-monitor]"  string => "/etc/init.d/lvm2-monitor reload";
  "stopcommand[lvm2-monitor]"    string => "/etc/init.d/lvm2-monitor stop";
  "pattern[lvm2-monitor]"        string => ".*vgchange.*";

  "startcommand[mcstrans]"   string => "/etc/init.d/mcstrans start";
  "restartcommand[mcstrans]" string => "/etc/init.d/mcstrans restart";
  "reloadcommand[mcstrans]"  string => "/etc/init.d/mcstrans reload";
  "stopcommand[mcstrans]"    string => "/etc/init.d/mcstrans stop";
  "pattern[mcstrans]"        string => ".*mcstransd.*";

  "startcommand[mdmonitor]"   string => "/etc/init.d/mdmonitor start";
  "restartcommand[mdmonitor]" string => "/etc/init.d/mdmonitor restart";
  "reloadcommand[mdmonitor]"  string => "/etc/init.d/mdmonitor reload";
  "stopcommand[mdmonitor]"    string => "/etc/init.d/mdmonitor stop";
  "pattern[mdmonitor]"        string => ".*mdadm.*";

  "startcommand[mdmpd]"   string => "/etc/init.d/mdmpd start";
  "restartcommand[mdmpd]" string => "/etc/init.d/mdmpd restart";
  "reloadcommand[mdmpd]"  string => "/etc/init.d/mdmpd reload";
  "stopcommand[mdmpd]"    string => "/etc/init.d/mdmpd stop";
  "pattern[mdmpd]"        string => ".*mdmpd.*";

  "startcommand[messagebus]"   string => "/etc/init.d/messagebus start";
  "restartcommand[messagebus]" string => "/etc/init.d/messagebus restart";
  "reloadcommand[messagebus]"  string => "/etc/init.d/messagebus reload";
  "stopcommand[messagebus]"    string => "/etc/init.d/messagebus stop";
  "pattern[messagebus]"        string => ".*dbus-daemon.*";

  "startcommand[microcode_ctl]"   string => "/etc/init.d/microcode_ctl start";
  "restartcommand[microcode_ctl]" string => "/etc/init.d/microcode_ctl restart";
  "reloadcommand[microcode_ctl]"  string => "/etc/init.d/microcode_ctl reload";
  "stopcommand[microcode_ctl]"    string => "/etc/init.d/microcode_ctl stop";
  "pattern[microcode_ctl]"        string => ".*microcode_ctl.*";

  "startcommand[multipathd]"   string => "/etc/init.d/multipathd start";
  "restartcommand[multipathd]" string => "/etc/init.d/multipathd restart";
  "reloadcommand[multipathd]"  string => "/etc/init.d/multipathd reload";
  "stopcommand[multipathd]"    string => "/etc/init.d/multipathd stop";
  "pattern[multipathd]"        string => ".*multipathd.*";

  "startcommand[mysql]"   string => "/etc/init.d/mysqld start";
  "restartcommand[mysql]" string => "/etc/init.d/mysqld restart";
  "reloadcommand[mysql]"  string => "/etc/init.d/mysqld reload";
  "stopcommand[mysql]"    string => "/etc/init.d/mysqld stop";
  "pattern[mysql]"        string => ".*mysqld.*";

  "startcommand[netplugd]"   string => "/etc/init.d/netplugd start";
  "restartcommand[netplugd]" string => "/etc/init.d/netplugd restart";
  "reloadcommand[netplugd]"  string => "/etc/init.d/netplugd reload";
  "stopcommand[netplugd]"    string => "/etc/init.d/netplugd stop";
  "pattern[netplugd]"        string => ".*netplugd.*";

  "startcommand[NetworkManager]"   string => "/etc/init.d/NetworkManager start";
  "restartcommand[NetworkManager]" string => "/etc/init.d/NetworkManager restart";
  "reloadcommand[NetworkManager]"  string => "/etc/init.d/NetworkManager reload";
  "stopcommand[NetworkManager]"    string => "/etc/init.d/NetworkManager stop";
  "pattern[NetworkManager]"        string => ".*NetworkManager.*";

  "startcommand[nfs]"   string => "/etc/init.d/nfs start";
  "restartcommand[nfs]" string => "/etc/init.d/nfs restart";
  "reloadcommand[nfs]"  string => "/etc/init.d/nfs reload";
  "stopcommand[nfs]"    string => "/etc/init.d/nfs stop";
  "pattern[nfs]"        string => ".*nfsd.*";

  "startcommand[nfslock]"   string => "/etc/init.d/nfslock start";
  "restartcommand[nfslock]" string => "/etc/init.d/nfslock restart";
  "reloadcommand[nfslock]"  string => "/etc/init.d/nfslock reload";
  "stopcommand[nfslock]"    string => "/etc/init.d/nfslock stop";
  "pattern[nfslock]"        string => ".*rpc.statd.*";

  "startcommand[nscd]"   string => "/etc/init.d/nscd start";
  "restartcommand[nscd]" string => "/etc/init.d/nscd restart";
  "reloadcommand[nscd]"  string => "/etc/init.d/nscd reload";
  "stopcommand[nscd]"    string => "/etc/init.d/nscd stop";
  "pattern[nscd]"        string => ".*nscd.*";

  "startcommand[oddjobd]"   string => "/etc/init.d/oddjobd start";
  "restartcommand[oddjobd]" string => "/etc/init.d/oddjobd restart";
  "reloadcommand[oddjobd]"  string => "/etc/init.d/oddjobd reload";
  "stopcommand[oddjobd]"    string => "/etc/init.d/oddjobd stop";
  "pattern[oddjobd]"        string => ".*oddjobd.*";

  "startcommand[pand]"   string => "/etc/init.d/pand start";
  "restartcommand[pand]" string => "/etc/init.d/pand restart";
  "reloadcommand[pand]"  string => "/etc/init.d/pand reload";
  "stopcommand[pand]"    string => "/etc/init.d/pand stop";
  "pattern[pand]"        string => ".*pand.*";

  "startcommand[pcscd]"   string => "/etc/init.d/pcscd start";
  "restartcommand[pcscd]" string => "/etc/init.d/pcscd restart";
  "reloadcommand[pcscd]"  string => "/etc/init.d/pcscd reload";
  "stopcommand[pcscd]"    string => "/etc/init.d/pcscd stop";
  "pattern[pcscd]"        string => ".*pcscd.*";

  "startcommand[portmap]"   string => "/etc/init.d/portmap start";
  "restartcommand[portmap]" string => "/etc/init.d/portmap restart";
  "reloadcommand[portmap]"  string => "/etc/init.d/portmap reload";
  "stopcommand[portmap]"    string => "/etc/init.d/portmap stop";
  "pattern[portmap]"        string => ".*portmap.*";

  "startcommand[postgresql]"   string => "/etc/init.d/postgresql start";
  "restartcommand[postgresql]" string => "/etc/init.d/postgresql restart";
  "reloadcommand[postgresql]"  string => "/etc/init.d/postgresql reload";
  "stopcommand[postgresql]"    string => "/etc/init.d/postgresql stop";
  "pattern[postgresql]"        string => ".*postmaster.*";

  "startcommand[rdisc]"   string => "/etc/init.d/rdisc start";
  "restartcommand[rdisc]" string => "/etc/init.d/rdisc restart";
  "reloadcommand[rdisc]"  string => "/etc/init.d/rdisc reload";
  "stopcommand[rdisc]"    string => "/etc/init.d/rdisc stop";
  "pattern[rdisc]"        string => ".*rdisc.*";

  "startcommand[rdisc]"   string => "/etc/init.d/rdisc start";
  "restartcommand[rdisc]" string => "/etc/init.d/rdisc restart";
  "reloadcommand[rdisc]"  string => "/etc/init.d/rdisc reload";
  "stopcommand[rdisc]"    string => "/etc/init.d/rdisc stop";
  "pattern[rdisc]"        string => ".*rdisc.*";

  "startcommand[readahead_early]"   string => "/etc/init.d/readahead_early start";
  "restartcommand[readahead_early]" string => "/etc/init.d/readahead_early restart";
  "reloadcommand[readahead_early]"  string => "/etc/init.d/readahead_early reload";
  "stopcommand[readahead_early]"    string => "/etc/init.d/readahead_early stop";
  "pattern[readahead_early]"        string => ".*readahead.*early.*";

  "startcommand[readahead_later]"   string => "/etc/init.d/readahead_later start";
  "restartcommand[readahead_later]" string => "/etc/init.d/readahead_later restart";
  "reloadcommand[readahead_later]"  string => "/etc/init.d/readahead_later reload";
  "stopcommand[readahead_later]"    string => "/etc/init.d/readahead_later stop";
  "pattern[readahead_later]"        string => ".*readahead.*later.*";

  "startcommand[restorecond]"   string => "/etc/init.d/restorecond start";
  "restartcommand[restorecond]" string => "/etc/init.d/restorecond restart";
  "reloadcommand[restorecond]"  string => "/etc/init.d/restorecond reload";
  "stopcommand[restorecond]"    string => "/etc/init.d/restorecond stop";
  "pattern[restorecond]"        string => ".*restorecond.*";

  "startcommand[rpcgssd]"   string => "/etc/init.d/rpcgssd start";
  "restartcommand[rpcgssd]" string => "/etc/init.d/rpcgssd restart";
  "reloadcommand[rpcgssd]"  string => "/etc/init.d/rpcgssd reload";
  "stopcommand[rpcgssd]"    string => "/etc/init.d/rpcgssd stop";
  "pattern[rpcgssd]"        string => ".*rpc.gssd.*";

  "startcommand[rpcidmapd]"   string => "/etc/init.d/rpcidmapd start";
  "restartcommand[rpcidmapd]" string => "/etc/init.d/rpcidmapd restart";
  "reloadcommand[rpcidmapd]"  string => "/etc/init.d/rpcidmapd reload";
  "stopcommand[rpcidmapd]"    string => "/etc/init.d/rpcidmapd stop";
  "pattern[rpcidmapd]"        string => ".*rpc.idmapd.*";

  "startcommand[rpcsvcgssd]"   string => "/etc/init.d/rpcsvcgssd start";
  "restartcommand[rpcsvcgssd]" string => "/etc/init.d/rpcsvcgssd restart";
  "reloadcommand[rpcsvcgssd]"  string => "/etc/init.d/rpcsvcgssd reload";
  "stopcommand[rpcsvcgssd]"    string => "/etc/init.d/rpcsvcgssd stop";
  "pattern[rpcsvcgssd]"        string => ".*rpc.svcgssd.*";

  "startcommand[saslauthd]"   string => "/etc/init.d/saslauthd start";
  "restartcommand[saslauthd]" string => "/etc/init.d/saslauthd restart";
  "reloadcommand[saslauthd]"  string => "/etc/init.d/saslauthd reload";
  "stopcommand[saslauthd]"    string => "/etc/init.d/saslauthd stop";
  "pattern[saslauthd]"        string => ".*saslauthd.*";

  "startcommand[smartd]"   string => "/etc/init.d/smartd start";
  "restartcommand[smartd]" string => "/etc/init.d/smartd restart";
  "reloadcommand[smartd]"  string => "/etc/init.d/smartd reload";
  "stopcommand[smartd]"    string => "/etc/init.d/smartd stop";
  "pattern[smartd]"        string => ".*smartd.*";

  "startcommand[svnserve]"   string => "/etc/init.d/svnserve start";
  "restartcommand[svnserve]" string => "/etc/init.d/svnserve restart";
  "reloadcommand[svnserve]"  string => "/etc/init.d/svnserve reload";
  "stopcommand[svnserve]"    string => "/etc/init.d/svnserve stop";
  "pattern[svnserve]"        string => ".*svnserve.*";

  "startcommand[syslog]"   string => "/etc/init.d/syslog start";
  "restartcommand[syslog]" string => "/etc/init.d/syslog restart";
  "reloadcommand[syslog]"  string => "/etc/init.d/syslog reload";
  "stopcommand[syslog]"    string => "/etc/init.d/syslog stop";
  "pattern[syslog]"        string => ".*syslogd.*";

  "startcommand[tcsd]"   string => "/etc/init.d/tcsd start";
  "restartcommand[tcsd]" string => "/etc/init.d/tcsd restart";
  "reloadcommand[tcsd]"  string => "/etc/init.d/tcsd reload";
  "stopcommand[tcsd]"    string => "/etc/init.d/tcsd stop";
  "pattern[tcsd]"        string => ".*tcsd.*";

  "startcommand[www]"   string => "/etc/init.d/httpd start";
  "restartcommand[www]" string => "/etc/init.d/httpd restart";
  "reloadcommand[www]"  string => "/etc/init.d/httpd reload";
  "stopcommand[www]"    string => "/etc/init.d/httpd stop";
  "pattern[www]"        string => ".*httpd.*";

  "startcommand[xfs]"   string => "/etc/init.d/xfs start";
  "restartcommand[xfs]" string => "/etc/init.d/xfs restart";
  "reloadcommand[xfs]"  string => "/etc/init.d/xfs reload";
  "stopcommand[xfs]"    string => "/etc/init.d/xfs stop";
  "pattern[xfs]"        string => ".*xfs.*";

  "startcommand[ypbind]"   string => "/etc/init.d/ypbind start";
  "restartcommand[ypbind]" string => "/etc/init.d/ypbind restart";
  "reloadcommand[ypbind]"  string => "/etc/init.d/ypbind reload";
  "stopcommand[ypbind]"    string => "/etc/init.d/ypbind stop";
  "pattern[ypbind]"        string => ".*ypbind.*";

  "startcommand[yum-updatesd]"   string => "/etc/init.d/yum-updatesd start";
  "restartcommand[yum-updatesd]" string => "/etc/init.d/yum-updatesd restart";
  "reloadcommand[yum-updatesd]"  string => "/etc/init.d/yum-updatesd reload";
  "stopcommand[yum-updatesd]"    string => "/etc/init.d/yum-updatesd stop";
  "pattern[yum-updatesd]"        string => ".*yum-updatesd.*";

  "startcommand[ssh]"   string => "/etc/init.d/sshd start";
  "restartcommand[ssh]" string => "/etc/init.d/sshd restart";
  "reloadcommand[ssh]"  string => "/etc/init.d/sshd reload";
  "stopcommand[ssh]"    string => "/etc/init.d/sshd stop";
  "pattern[ssh]"        string => ".*sshd.*";

 debian|ubuntu::

  "startcommand[atd]"   string => "/etc/init.d/atd start";
  "restartcommand[atd]" string => "/etc/init.d/atd restart";
  "reloadcommand[atd]"  string => "/etc/init.d/atd reload";
  "stopcommand[atd]"    string => "/etc/init.d/atd stop";
  "pattern[atd]"        string => "atd.*";

  "startcommand[bluetoothd]"   string => "/etc/init.d/bluetoothd start";
  "restartcommand[bluetoothd]" string => "/etc/init.d/bluetoothd restart";
  "reloadcommand[bluetoothd]"  string => "/etc/init.d/bluetoothd reload";
  "stopcommand[bluetoothd]"    string => "/etc/init.d/bluetoothd stop";
  "pattern[bluetoothd]"        string => ".*bluetoothd.*";

  "startcommand[bootlogd]"   string => "/etc/init.d/bootlogd start";
  "restartcommand[bootlogd]" string => "/etc/init.d/bootlogd restart";
  "reloadcommand[bootlogd]"  string => "/etc/init.d/bootlogd reload";
  "stopcommand[bootlogd]"    string => "/etc/init.d/bootlogd stop";
  "pattern[bootlogd]"        string => ".*bootlogd.*";

  "startcommand[crond]"   string => "/etc/init.d/cron start";
  "restartcommand[crond]" string => "/etc/init.d/cron restart";
  "reloadcommand[crond]"  string => "/etc/init.d/cron reload";
  "stopcommand[crond]"    string => "/etc/init.d/cron stop";
  "pattern[crond]"        string => ".*cron.*";

  "startcommand[kerneloops]"   string => "/etc/init.d/kerneloops start";
  "restartcommand[kerneloops]" string => "/etc/init.d/kerneloops restart";
  "reloadcommand[kerneloops]"  string => "/etc/init.d/kerneloops reload";
  "stopcommand[kerneloops]"    string => "/etc/init.d/kerneloops stop";
  "pattern[kerneloops]"        string => ".*kerneloops.*";

  "startcommand[mysql]"   string => "/etc/init.d/mysql start";
  "restartcommand[mysql]" string => "/etc/init.d/mysql restart";
  "reloadcommand[mysql]"  string => "/etc/init.d/mysql reload";
  "stopcommand[mysql]"    string => "/etc/init.d/mysql stop";
  "pattern[mysql]"        string => ".*mysqld.*";

  "startcommand[NetworkManager]"   string => "/etc/init.d/network-manager start";
  "restartcommand[NetworkManager]" string => "/etc/init.d/network-manager restart";
  "reloadcommand[NetworkManager]"  string => "/etc/init.d/network-manager reload";
  "stopcommand[NetworkManager]"    string => "/etc/init.d/network-manager stop";
  "pattern[NetworkManager]"        string => ".*NetworkManager.*";

  "startcommand[ondemand]"   string => "/etc/init.d/ondemand start";
  "restartcommand[ondemand]" string => "/etc/init.d/ondemand restart";
  "reloadcommand[ondemand]"  string => "/etc/init.d/ondemand reload";
  "stopcommand[ondemand]"    string => "/etc/init.d/ondemand stop";
  "pattern[ondemand]"        string => ".*ondemand.*";

  "startcommand[plymouth]"   string => "/etc/init.d/plymouthd start";
  "restartcommand[plymouth]" string => "/etc/init.d/plymouthd restart";
  "reloadcommand[plymouth]"  string => "/etc/init.d/plymouthd reload";
  "stopcommand[plymouth]"    string => "/etc/init.d/plymouthd stop";
  "pattern[plymouth]"        string => ".*plymouthd.*";

  "startcommand[postgresql84]"   string => "/etc/init.d/postgresql-8.4 start";
  "restartcommand[postgresql84]" string => "/etc/init.d/postgresql-8.4 restart";
  "reloadcommand[postgresql84]"  string => "/etc/init.d/postgresql-8.4 reload";
  "stopcommand[postgresql84]"    string => "/etc/init.d/postgresql-8.4 stop";
  "pattern[postgresql84]"        string => ".*postgresql.*";

  "startcommand[postgresql91]"   string => "/etc/init.d/postgresql-9.1 start";
  "restartcommand[postgresql91]" string => "/etc/init.d/postgresql-9.1 restart";
  "reloadcommand[postgresql91]"  string => "/etc/init.d/postgresql-9.1 reload";
  "stopcommand[postgresql91]"    string => "/etc/init.d/postgresql-9.1 stop";
  "pattern[postgresql91]"        string => ".*postgresql.*";

  "startcommand[saned]"   string => "/etc/init.d/saned start";
  "restartcommand[saned]" string => "/etc/init.d/saned restart";
  "reloadcommand[saned]"  string => "/etc/init.d/saned reload";
  "stopcommand[saned]"    string => "/etc/init.d/saned stop";
  "pattern[saned]"        string => ".*saned.*";

  "startcommand[udev]"   string => "/etc/init.d/udev start";
  "restartcommand[udev]" string => "/etc/init.d/udev restart";
  "reloadcommand[udev]"  string => "/etc/init.d/udev reload";
  "stopcommand[udev]"    string => "/etc/init.d/udev stop";
  "pattern[udev]"        string => ".*udev.*";

  "startcommand[udevmonitor]"   string => "/etc/init.d/udevmonitor start";
  "restartcommand[udevmonitor]" string => "/etc/init.d/udevmonitor restart";
  "reloadcommand[udevmonitor]"  string => "/etc/init.d/udevmonitor reload";
  "stopcommand[udevmonitor]"    string => "/etc/init.d/udevmonitor stop";
  "pattern[udevmonitor]"        string => ".*udevadm.*monitor.*";

  "startcommand[www]"   string => "/etc/init.d/apache2 start";
  "restartcommand[www]" string => "/etc/init.d/apache2 restart";
  "reloadcommand[www]"  string => "/etc/init.d/apache2 reload";
  "stopcommand[www]"    string => "/etc/init.d/apache2 stop";
  "pattern[www]"        string => ".*apache2.*";

  "startcommand[ssh]"   string => "/etc/init.d/ssh start";
  "restartcommand[ssh]" string => "/etc/init.d/ssh restart";
  "reloadcommand[ssh]"  string => "/etc/init.d/ssh reload";
  "stopcommand[ssh]"    string => "/etc/init.d/ssh stop";
  "pattern[ssh]"        string => ".*sshd.*";


 # METHODS that implement these ............................................

classes:

  "start" expression => strcmp("start","$(state)"),
             comment => "Check if to start a service";
  "restart" expression => strcmp("restart","$(state)"),
             comment => "Check if to restart a service";
  "reload" expression => strcmp("reload","$(state)"),
             comment => "Check if to reload a service";
  "stop"  expression => strcmp("stop","$(state)"),
             comment => "Check if to stop a service";

# Do we want to include the packages here too?

processes:

  start::

    "$(pattern[$(service)])" ->  { "@(stakeholders[$(service)])" }

             comment => "Verify that the service appears in the process table",
       restart_class => "start_$(service)";

  stop::

    "$(pattern[$(service)])" -> { "@(stakeholders[$(service)])" }

            comment => "Verify that the service does not appear in the process",
       process_stop => "$(stopcommand[$(service)])",
            signals => { "term", "kill"};

commands:

  "$(startcommand[$(service)])" -> { "@(stakeholders[$(service)])" }

            comment => "Execute command to start the $(service) service",
         ifvarclass => canonify("start_$(service)");

  restart::
    "$(restartcommand[$(service)])" -> { "@(stakeholders[$(service)])" }

            comment => "Execute command to restart the $(service) service";

  reload::
    "$(reloadcommand[$(service)])" -> { "@(stakeholders[$(service)])" }

            comment => "Execute command to reload the $(service) service";
}


##-------------------------------------------------------
## database promises
##-------------------------------------------------------

body database_server local_mysql(username, password)
{
db_server_owner => "$(username)";
db_server_password => "$(password)";
db_server_host => "localhost";
db_server_type => "mysql";
db_server_connection_db => "mysql";
}

##

body database_server local_postgresql(username, password)
{
db_server_owner => "$(username)";
db_server_password => "$(password)";
db_server_host => "localhost";
db_server_type => "postgres";
db_server_connection_db => "postgres";
}

##-------------------------------------------------------
## guest_environment promises
##-------------------------------------------------------

body environment_resources kvm(name, arch, cpu_count, mem_kb, disk_file)
{
env_spec =>
"<domain type='kvm'>
  <name>$(name)</name>
  <memory>$(mem_kb)</memory>
  <currentMemory>$(mem_kb)</currentMemory>
  <vcpu>$(cpu_count)</vcpu>
  <os>
    <type arch='$(arch)'>hvm</type>
  </os>
  <features>
    <acpi/>
    <apic/>
    <pae/>
  </features>
  <on_poweroff>destroy</on_poweroff>
  <on_reboot>restart</on_reboot>
  <on_crash>restart</on_crash>
  <devices>
    <emulator>/usr/bin/kvm</emulator>
    <disk type='file' device='disk'>
      <source file='$(disk_file)'/>
      <target dev='vda' bus='virtio'/>
    </disk>
    <interface type='network'>
      <source network='default'/>
    </interface>
    <input type='mouse' bus='ps2'/>
    <graphics type='vnc' port='-1' autoport='yes'/>
  </devices>
</domain>";
}


####################################################
## monitor bodyparts
####################################################

body match_value scan_log(line)
{
select_line_matching => "$(line)";
track_growing_file => "true";
}

##

body match_value scan_changing_file(line)
{
select_line_matching => "$(line)";
track_growing_file => "false";
}

##

body match_value single_value(regex)
{
select_line_matching => "$(regex)";
extraction_regex => "($(regex))";
}

##

body match_value line_match_value(line_match, extract_regex)
{
select_line_matching => "$(line_match)";
extraction_regex => "$(extract_regex)";
}

##

body action sample_rate(x)
{
ifelapsed => "$(x)";
expireafter => "10";
}


bundle common paths
# In addition to defining common paths, this bundle also defines context
# classes for paths that are defined (_stdlib_has_path_xxx) and paths that
# exist (_stdlib_path_exists_xxx)
{
  vars:

      #
      # Common full pathname of commands for OS
      #

    any::
      "path[getfacl]"  string => "/usr/bin/getfacl";

    aix::

      "path[awk]"      string => "/usr/bin/awk";
      "path[bc]"       string => "/usr/bin/bc";
      "path[cat]"      string => "/bin/cat";
      "path[cksum]"    string => "/usr/bin/cksum";
      "path[crontabs]" string => "/var/spool/cron/crontabs";
      "path[cut]"      string => "/usr/bin/cut";
      "path[dc]"       string => "/usr/bin/dc";
      "path[df]"       string => "/usr/bin/df";
      "path[diff]"     string => "/usr/bin/diff";
      "path[dig]"      string => "/usr/bin/dig";
      "path[echo]"     string => "/usr/bin/echo";
      "path[egrep]"    string => "/usr/bin/egrep";
      "path[find]"     string => "/usr/bin/find";
      "path[grep]"     string => "/usr/bin/grep";
      "path[ls]"       string => "/usr/bin/ls";
      "path[netstat]"  string => "/usr/bin/netstat";
      "path[ping]"     string => "/usr/bin/ping";
      "path[perl]"     string => "/usr/bin/perl";
      "path[printf]"   string => "/usr/bin/printf";
      "path[sed]"      string => "/usr/bin/sed";
      "path[sort]"     string => "/usr/bin/sort";
      "path[tr]"       string => "/usr/bin/tr";

    freebsd|netbsd::

      "path[awk]"      string => "/usr/bin/awk";
      "path[bc]"       string => "/usr/bin/bc";
      "path[cat]"      string => "/bin/cat";
      "path[cksum]"    string => "/usr/bin/cksum";
      "path[crontabs]" string => "/var/cron/tabs";
      "path[cut]"      string => "/usr/bin/cut";
      "path[dc]"       string => "/usr/bin/dc";
      "path[df]"       string => "/bin/df";
      "path[diff]"     string => "/usr/bin/diff";
      "path[dig]"      string => "/usr/bin/dig";
      "path[echo]"     string => "/bin/echo";
      "path[egrep]"    string => "/usr/bin/egrep";
      "path[find]"     string => "/usr/bin/find";
      "path[grep]"     string => "/usr/bin/grep";
      "path[ls]"       string => "/bin/ls";
      "path[netstat]"  string => "/usr/bin/netstat";
      "path[ping]"     string => "/usr/bin/ping";
      "path[perl]"     string => "/usr/bin/perl";
      "path[printf]"   string => "/usr/bin/printf";
      "path[sed]"      string => "/usr/bin/sed";
      "path[sort]"     string => "/usr/bin/sort";
      "path[tr]"       string => "/usr/bin/tr";

    openbsd::

      "path[awk]"      string => "/usr/bin/awk";
      "path[bc]"       string => "/usr/bin/bc";
      "path[cat]"      string => "/bin/cat";
      "path[cksum]"    string => "/bin/cksum";
      "path[crontabs]" string => "/var/cron/tabs";
      "path[cut]"      string => "/usr/bin/cut";
      "path[dc]"       string => "/usr/bin/dc";
      "path[df]"       string => "/bin/df";
      "path[diff]"     string => "/usr/bin/diff";
      "path[dig]"      string => "/usr/sbin/dig";
      "path[echo]"     string => "/bin/echo";
      "path[egrep]"    string => "/usr/bin/egrep";
      "path[find]"     string => "/usr/bin/find";
      "path[grep]"     string => "/usr/bin/grep";
      "path[ls]"       string => "/bin/ls";
      "path[netstat]"  string => "/usr/bin/netstat";
      "path[ping]"     string => "/usr/bin/ping";
      "path[perl]"     string => "/usr/bin/perl";
      "path[printf]"   string => "/usr/bin/printf";
      "path[sed]"      string => "/usr/bin/sed";
      "path[sort]"     string => "/usr/bin/sort";
      "path[tr]"       string => "/usr/bin/tr";

    solaris::

      "path[awk]"      string => "/usr/bin/awk";
      "path[bc]"       string => "/usr/bin/bc";
      "path[cat]"      string => "/usr/bin/cat";
      "path[cksum]"    string => "/usr/bin/cksum";
      "path[crontabs]" string => "/var/spool/cron/crontabs";
      "path[cut]"      string => "/usr/bin/cut";
      "path[dc]"       string => "/usr/bin/dc";
      "path[df]"       string => "/usr/bin/df";
      "path[diff]"     string => "/usr/bin/diff";
      "path[dig]"      string => "/usr/sbin/dig";
      "path[echo]"     string => "/usr/bin/echo";
      "path[egrep]"    string => "/usr/bin/egrep";
      "path[find]"     string => "/usr/bin/find";
      "path[grep]"     string => "/usr/bin/grep";
      "path[ls]"       string => "/usr/bin/ls";
      "path[netstat]"  string => "/usr/bin/netstat";
      "path[ping]"     string => "/usr/bin/ping";
      "path[perl]"     string => "/usr/bin/perl";
      "path[printf]"   string => "/usr/bin/printf";
      "path[sed]"      string => "/usr/bin/sed";
      "path[sort]"     string => "/usr/bin/sort";
      "path[tr]"       string => "/usr/bin/tr";
      #
      "path[svcs]"     string => "/usr/bin/svcs";
      "path[svcadm]"   string => "/usr/sbin/svcadm";

    redhat::

      "path[awk]"           string => "/bin/awk";
      "path[bc]"            string => "/usr/bin/bc";
      "path[cat]"           string => "/bin/cat";
      "path[cksum]"         string => "/usr/bin/cksum";
      "path[createrepo]"    string => "/usr/bin/createrepo";
      "path[crontab]"       string => "/usr/bin/crontab";
      "path[crontabs]"      string => "/var/spool/cron";
      "path[cut]"           string => "/bin/cut";
      "path[dc]"            string => "/usr/bin/dc";
      "path[df]"            string => "/bin/df";
      "path[diff]"          string => "/usr/bin/diff";
      "path[dig]"           string => "/usr/bin/dig";
      "path[domainname]"    string => "/bin/domainname";
      "path[echo]"          string => "/bin/echo";
      "path[egrep]"         string => "/bin/egrep";
      "path[find]"          string => "/usr/bin/find";
      "path[grep]"          string => "/bin/grep";
      "path[hostname]"      string => "/bin/hostname";
      "path[init]"          string => "/sbin/init";
      "path[iptables]"      string => "/sbin/iptables";
      "path[iptables_save]" string => "/sbin/iptables-save";
      "path[ls]"            string => "/bin/ls";
      "path[netstat]"       string => "/bin/netstat";
      "path[ping]"          string => "/usr/bin/ping";
      "path[perl]"          string => "/usr/bin/perl";
      "path[printf]"        string => "/usr/bin/printf";
      "path[sed]"           string => "/bin/sed";
      "path[sort]"          string => "/bin/sort";
      "path[sysctl]"        string => "/sbin/sysctl";
      "path[test]"          string => "/usr/bin/test";
      "path[tr]"            string => "/usr/bin/tr";

      #
      "path[chkconfig]" string => "/sbin/chkconfig";
      "path[groupadd]"  string => "/usr/sbin/groupadd";
      "path[groupdel]"  string => "/usr/sbin/groupdel";
      "path[ifconfig]"  string => "/sbin/ifconfig";
      "path[ip]"        string => "/sbin/ip";
      "path[rpm]"       string => "/bin/rpm";
      "path[service]"   string => "/sbin/service";
      "path[svc]"       string => "/sbin/service";
      "path[useradd]"   string => "/usr/sbin/useradd";
      "path[userdel]"   string => "/usr/sbin/userdel";
      "path[yum]"       string => "/usr/bin/yum";

    debian::

      "path[awk]"           string => "/usr/bin/awk";
      "path[bc]"            string => "/usr/bin/bc";
      "path[cat]"           string => "/bin/cat";
      "path[chkconfig]"     string => "/sbin/chkconfig";
      "path[cksum]"         string => "/usr/bin/cksum";
      "path[createrepo]"    string => "/usr/bin/createrepo";
      "path[crontab]"       string => "/usr/bin/crontab";
      "path[crontabs]"      string => "/var/spool/cron/crontabs";
      "path[cut]"           string => "/usr/bin/cut";
      "path[dc]"            string => "/usr/bin/dc";
      "path[df]"            string => "/bin/df";
      "path[diff]"          string => "/usr/bin/diff";
      "path[dig]"           string => "/usr/bin/dig";
      "path[dmidecode]"     string => "/usr/sbin/dmidecode";
      "path[domainname]"    string => "/bin/domainname";
      "path[echo]"          string => "/bin/echo";
      "path[egrep]"         string => "/bin/egrep";
      "path[find]"          string => "/usr/bin/find";
      "path[grep]"          string => "/bin/grep";
      "path[hostname]"      string => "/bin/hostname";
      "path[ls]"            string => "/bin/ls";
      "path[init]"          string => "/sbin/init";
      "path[iptables]"      string => "/sbin/iptables";
      "path[iptables_save]" string => "/sbin/iptables-save";
      "path[netstat]"       string => "/bin/netstat";
      "path[ping]"          string => "/bin/ping";
      "path[perl]"          string => "/usr/bin/perl";
      "path[printf]"        string => "/usr/bin/printf";
      "path[sed]"           string => "/bin/sed";
      "path[sort]"          string => "/usr/bin/sort";
      "path[sysctl]"        string => "/sbin/sysctl";
      "path[test]"          string => "/usr/bin/test";
      "path[tr]"            string => "/usr/bin/tr";

      #
      "path[apt_cache]"           string => "/usr/bin/apt-cache";
      "path[apt_config]"          string => "/usr/bin/apt-config";
      "path[apt_get]"             string => "/usr/bin/apt-get";
      "path[apt_key]"             string => "/usr/bin/apt-key";
      "path[aptitude]"            string => "/usr/bin/aptitude";
      "path[dpkg]"                string => "/usr/bin/dpkg";
      "path[groupadd]"            string => "/usr/sbin/groupadd";
      "path[ifconfig]"            string => "/sbin/ifconfig";
      "path[ip]"                  string => "/sbin/ip";
      "path[service]"             string => "/usr/sbin/service";
      "path[svc]"                 string => "/usr/sbin/service";
      "path[update_alternatives]" string => "/usr/bin/update-alternatives";
      "path[update_rc_d]"         string => "/usr/sbin/update-rc.d";
      "path[useradd]"             string => "/usr/sbin/useradd";


    any::
      "all_paths"     slist => getindices("path");
      "$(all_paths)" string => "$(path[$(all_paths)])";

  classes:
    "_stdlib_has_path_$(all_paths)"
      expression => isvariable("$(all_paths)"),
      comment    => "It's useful to know if a given path is defined";

    "_stdlib_path_exists_$(all_paths)"
      expression => fileexists("$(path[$(all_paths)])"),
      comment    => "It's useful to know if $(all_paths) exists on the filesystem as defined";
}
