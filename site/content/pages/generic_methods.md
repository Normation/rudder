Title: Generic methods
slugs: generic-methods
Author: Normation
# Permissions type recursion
* *Bundle name:* permissions_type_recursion

## Signature
* permissions_type_recursion(path, mode, owner, group, type, recursion)

## Parameters
* path
* mode
* owner
* group
* type
* recursion

## Classes defined
* permissions_$(path)

# File ensure lines in section
* *Bundle name:* file_ensure_lines_in_section_present

## Signature
* file_ensure_lines_in_section_present(file, section, content_tab)

## Parameters
* file
* section
* content_tab

## Classes defined
* file_ensure_lines_in_section_present_$(file)

# Logger for Rudder
* *Bundle name:* logger_rudder

## Signature
* logger_rudder(message, class_prefix)

## Parameters
* message
* class_prefix

## Classes defined
* logger_rudder_$(class_prefix)

# File replace lines
* *Bundle name:* file_replace_lines

## Signature
* file_replace_lines(file, line, replacement)

## Parameters
* file
* line
* replacement

## Classes defined
* file_replace_lines_$(file)

# Service restart
* *Bundle name:* service_restart

## Signature
* service_restart(service_name)

## Parameters
* service_name

## Classes defined
* service_restart_$(service_name)

# Service check running ps
* *Bundle name:* service_check_running_ps

## Signature
* service_check_running_ps(service_regex)

## Parameters
* service_regex

## Classes defined
* service_check_running_$(service_regex)

# Permissions recurse
* *Bundle name:* permissions_recurse

## Signature
* permissions_recurse(path, mode, owner, group)

## Parameters
* path
* mode
* owner
* group

## Classes defined
* permissions_$(path)

# Service ensure stopped
* *Bundle name:* service_ensure_stopped

## Signature
* service_ensure_stopped(service_name)

## Parameters
* service_name

## Classes defined
* service_ensure_stopped_$(service_name)

# File copy from local source recurse
* *Bundle name:* file_copy_from_local_source_recursion

## Signature
* file_copy_from_local_source_recursion(source, destination, recursion)

## Parameters
* source
* destination
* recursion

## Classes defined
* file_copy_from_local_source_$(destination)

# Service stop
* *Bundle name:* service_stop

## Signature
* service_stop(service_name)

## Parameters
* service_name

## Classes defined
* service_stop_$(service_name)

# File check exists
* *Bundle name:* file_check_exists

## Signature
* file_check_exists(file_name)

## Parameters
* file_name

## Classes defined
* file_check_exists_$(file_name)

# Service reload
* *Bundle name:* service_reload

## Signature
* service_reload(service_name)

## Parameters
* service_name

## Classes defined
* service_reload_$(service_name)

# Command execution
* *Bundle name:* command_execution

## Signature
* command_execution(command_name)

## Parameters
* command_name

## Classes defined
* command_execution_$(command_name)

# File from template
* *Bundle name:* file_from_template

## Signature
* file_from_template(source_template, destination)

## Parameters
* source_template
* destination

## Classes defined
* file_from_template_$(destination)

# Package verify
* *Bundle name:* package_verify

## Signature
* package_verify(package_name)

## Parameters
* package_name

## Classes defined
* package_install_$(package_name)

# Permissions dirs
* *Bundle name:* permissions_dirs

## Signature
* permissions_dirs(path, mode, owner, group)

## Parameters
* path
* mode
* owner
* group

## Classes defined
* permissions_$(path)

# File ensure lines absent
* *Bundle name:* file_ensure_lines_absent

## Signature
* file_ensure_lines_absent(file, lines)

## Parameters
* file
* lines

## Classes defined
* file_ensure_lines_absent_$(file)

# Service check running
* *Bundle name:* service_check_running

## Signature
* service_check_running(service_name)

## Parameters
* service_name

## Classes defined
* service_check_running_$(service_name)

# Package install
* *Bundle name:* package_install

## Signature
* package_install(package_name)

## Parameters
* package_name

## Classes defined
* package_install_$(package_name)

# File copy from remote source recurse
* *Bundle name:* file_copy_from_remote_source_recursion

## Signature
* file_copy_from_remote_source_recursion(source, destination, recursion)

## Parameters
* source
* destination
* recursion

## Classes defined
* file_copy_from_remote_source_$(destination)

# File ensure lines present
* *Bundle name:* file_ensure_lines_present

## Signature
* file_ensure_lines_present(file, lines)

## Parameters
* file
* lines

## Classes defined
* file_ensure_lines_present_$(file)

# File ensure block present
* *Bundle name:* file_ensure_block_present

## Signature
* file_ensure_block_present(file, block)

## Parameters
* file
* block

## Classes defined
* file_ensure_block_present_$(file)

# File template expand
* *Bundle name:* file_template_expand

## Signature
* file_template_expand(tml_file, target_file, mode, owner, group)

## Parameters
* tml_file
* target_file
* mode
* owner
* group

## Classes defined
* file_template_expand_$(target_file)

# Create symlink (optional overwriting)
* *Bundle name:* file_create_symlink_enforce

## Signature
* file_create_symlink_enforce(source, destination, enforce)

## Parameters
* source
* destination
* enforce

## Classes defined
* file_create_symlink_$(destination)

# Package install version
* *Bundle name:* package_install_version

## Signature
* package_install_version(package_name, package_version)

## Parameters
* package_name
* package_version

## Classes defined
* package_install_$(package_name)

# Create symlink
* *Bundle name:* file_create_symlink

## Signature
* file_create_symlink(source, destination)

## Parameters
* source
* destination

## Classes defined
* file_create_symlink_$(destination)

# File ensure block in section
* *Bundle name:* file_ensure_block_in_section

## Signature
* file_ensure_block_in_section(file, section_start, section_end, block)

## Parameters
* file
* section_start
* section_end
* block

## Classes defined
* file_ensure_block_in_section_$(file)

# File copy from remote source
* *Bundle name:* file_copy_from_remote_source

## Signature
* file_copy_from_remote_source(source, destination)

## Parameters
* source
* destination

## Classes defined
* file_copy_from_remote_source_$(destination)

# File copy from local source
* *Bundle name:* file_copy_from_local_source

## Signature
* file_copy_from_local_source(source, destination)

## Parameters
* source
* destination

## Classes defined
* file_copy_from_local_source_$(destination)

# Permissions dirs recurse
* *Bundle name:* permissions_dirs_recurse

## Signature
* permissions_dirs_recurse(path, mode, owner, group)

## Parameters
* path
* mode
* owner
* group

## Classes defined
* permissions_$(path)

# Package verify version
* *Bundle name:* package_verify_version

## Signature
* package_verify_version(package_name, package_version)

## Parameters
* package_name
* package_version

## Classes defined
* package_install_$(package_name)

# Package remove
* *Bundle name:* package_remove

## Signature
* package_remove(package_name)

## Parameters
* package_name

## Classes defined
* package_remove_$(package_name)

# Permissions recurse
* *Bundle name:* permissions

## Signature
* permissions(path, mode, owner, group)

## Parameters
* path
* mode
* owner
* group

## Classes defined
* permissions_$(path)

# Service start
* *Bundle name:* service_start

## Signature
* service_start(service_name)

## Parameters
* service_name

## Classes defined
* service_start_$(service_name)

# Service ensure running
* *Bundle name:* service_ensure_running

## Signature
* service_ensure_running(service_name)

## Parameters
* service_name

## Classes defined
* service_ensure_running_$(service_name)

# Create symlink (force overwrite)
* *Bundle name:* file_create_symlink_force

## Signature
* file_create_symlink_force(source, destination)

## Parameters
* source
* destination

## Classes defined
* file_create_symlink_$(destination)

# Package install version compare
* *Bundle name:* package_install_version_cmp

## Signature
* package_install_version_cmp(package_name, version_comparator, package_version, action)

## Parameters
* package_name
* version_comparator
* package_version
* action

## Classes defined
* package_install_$(package_name)

