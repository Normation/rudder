@format=0

resource variable(p0, p1)
resource group(p0)
resource permissions(p0)
resource environment(p0)
resource file(p0)
resource condition(p0)
resource package(p0)
resource user(p0)
resource http_request(p0, p1)
resource sysctl(p0)
resource kernel_module(p0)
resource monitoring(p0)
resource service(p0)
resource command(p0)
resource sharedfile(p0, p1)
resource schedule(p0)
resource directory(p0)

@class_parameter_index = 0
@class_parameter_name = "command"
@class_prefix = "command_execution"
@supported_formats = ["cf", "dsc"]
command state execution() {}

@class_parameter_index = 0
@class_parameter_name = "command"
@class_prefix = "command_execution_once"
@supported_formats = ["cf", "dsc"]
command state execution_once(ok_codes, until, unique_id) {}

@class_parameter_index = 0
@class_parameter_name = "command"
@class_prefix = "command_execution_result"
@supported_formats = ["cf", "dsc"]
command state execution_result(kept_codes, repaired_codes) {}

@class_parameter_index = 0
@class_parameter_name = "condition_prefix"
@class_prefix = "condition_from_command"
@supported_formats = ["cf", "dsc"]
condition state from_command(command, true_codes, false_codes) {}

@class_parameter_index = 0
@class_parameter_name = "condition_prefix"
@class_prefix = "condition_from_expression"
@supported_formats = ["cf", "dsc"]
condition state from_expression(condition_expression) {}

@class_parameter_index = 0
@class_parameter_name = "condition_prefix"
@class_prefix = "condition_from_expression_persistent"
@supported_formats = ["cf", "dsc"]
condition state from_expression_persistent(condition_expression, duration) {}

@class_parameter_index = 0
@class_parameter_name = "condition_prefix"
@class_prefix = "condition_from_variable_existence"
@supported_formats = ["cf", "dsc"]
condition state from_variable_existence(variable_name) {}

@class_parameter_index = 0
@class_parameter_name = "condition_prefix"
@class_prefix = "condition_from_variable_match"
@supported_formats = ["cf", "dsc"]
condition state from_variable_match(variable_name, expected_match) {}

@class_parameter_index = 0
@class_parameter_name = "condition"
@class_prefix = "condition_once"
@supported_formats = ["cf", "dsc"]
condition state once() {}

@class_parameter_index = 0
@class_parameter_name = "target"
@class_prefix = "directory_absent"
@supported_formats = ["cf", "dsc"]
directory state absent(recursive) {}

@class_parameter_index = 0
@class_parameter_name = "directory_name"
@class_prefix = "directory_check_exists"
@supported_formats = ["cf", "dsc"]
directory state check_exists() {}

@class_parameter_index = 0
@class_parameter_name = "target"
@class_prefix = "directory_create"
@supported_formats = ["cf", "dsc"]
directory state create() {}

@class_parameter_index = 0
@class_parameter_name = "target"
@class_prefix = "directory_present"
@supported_formats = ["cf", "dsc"]
directory state present() {}

@class_parameter_index = 0
@class_parameter_name = "name"
@class_prefix = "environment_variable_present"
@supported_formats = ["cf", "dsc"]
environment state variable_present(value) {}

@class_parameter_index = 0
@class_parameter_name = "target"
@class_prefix = "file_absent"
@supported_formats = ["cf", "dsc"]
file state absent() {}

@class_parameter_index = 1
@class_parameter_name = "variable_name"
@class_prefix = "file_augeas_commands"
@supported_formats = ["cf", "dsc"]
file state augeas_commands(variable_prefix, commands, autoload) {}

@class_parameter_index = 2
@class_parameter_name = "lens"
@class_prefix = "file_augeas_set"
@supported_formats = ["cf", "dsc"]
file state augeas_set(path, value, file) {}

@class_parameter_index = 0
@class_parameter_name = "file"
@class_prefix = "file_block_present"
@supported_formats = ["cf", "dsc"]
file state block_present(block) {}

@class_parameter_index = 0
@class_parameter_name = "file"
@class_prefix = "file_block_present_in_section"
@supported_formats = ["cf", "dsc"]
file state block_present_in_section(section_start, section_end, block) {}

@class_parameter_index = 0
@class_parameter_name = "file_name"
@class_prefix = "file_check_block_device"
@supported_formats = ["cf", "dsc"]
file state check_block_device() {}

@class_parameter_index = 0
@class_parameter_name = "file_name"
@class_prefix = "file_check_character_device"
@supported_formats = ["cf", "dsc"]
file state check_character_device() {}

@class_parameter_index = 0
@class_parameter_name = "file_name"
@class_prefix = "file_check_exists"
@supported_formats = ["cf", "dsc"]
file state check_exists() {}

@class_parameter_index = 0
@class_parameter_name = "file_name"
@class_prefix = "file_check_FIFO_pipe"
@supported_formats = ["cf", "dsc"]
file state check_FIFO_pipe() {}

@class_parameter_index = 0
@class_parameter_name = "file_name_1"
@class_prefix = "file_check_hardlink"
@supported_formats = ["cf", "dsc"]
file state check_hardlink(file_name_2) {}

@class_parameter_index = 0
@class_parameter_name = "file_name"
@class_prefix = "file_check_regular"
@supported_formats = ["cf", "dsc"]
file state check_regular() {}

@class_parameter_index = 0
@class_parameter_name = "file_name"
@class_prefix = "file_check_socket"
@supported_formats = ["cf", "dsc"]
file state check_socket() {}

@class_parameter_index = 0
@class_parameter_name = "file_name"
@class_prefix = "file_check_symlink"
@supported_formats = ["cf", "dsc"]
file state check_symlink() {}

@class_parameter_index = 0
@class_parameter_name = "symlink"
@class_prefix = "file_check_symlinkto"
@supported_formats = ["cf", "dsc"]
file state check_symlinkto(target) {}

@class_parameter_index = 0
@class_parameter_name = "file"
@class_prefix = "file_lines_present"
@supported_formats = ["cf", "dsc"]
file state content(lines, enforce) {}

@class_parameter_index = 1
@class_parameter_name = "destination"
@class_prefix = "file_copy_from_local_source"
@supported_formats = ["cf", "dsc"]
file state copy_from_local_source(source) {}

@class_parameter_index = 1
@class_parameter_name = "destination"
@class_prefix = "file_copy_from_local_source"
@supported_formats = ["cf", "dsc"]
file state copy_from_local_source_recursion(source, recursion) {}

@class_parameter_index = 1
@class_parameter_name = "destination"
@class_prefix = "file_copy_from_local_source_with_check"
@supported_formats = ["cf", "dsc"]
file state copy_from_local_source_with_check(source, check_command, rc_ok) {}

@class_parameter_index = 1
@class_parameter_name = "destination"
@class_prefix = "file_copy_from_remote_source"
@supported_formats = ["cf", "dsc"]
file state copy_from_remote_source(source) {}

@class_parameter_index = 1
@class_parameter_name = "destination"
@class_prefix = "file_copy_from_remote_source"
@supported_formats = ["cf", "dsc"]
file state copy_from_remote_source_recursion(source, recursion) {}

@class_parameter_index = 0
@class_parameter_name = "target"
@class_prefix = "file_create"
@supported_formats = ["cf", "dsc"]
file state create() {}

@class_parameter_index = 1
@class_parameter_name = "destination"
@class_prefix = "file_create_symlink"
@supported_formats = ["cf", "dsc"]
file state create_symlink(source) {}

@class_parameter_index = 1
@class_parameter_name = "destination"
@class_prefix = "file_create_symlink"
@supported_formats = ["cf", "dsc"]
file state create_symlink_enforce(source, enforce) {}

@class_parameter_index = 1
@class_parameter_name = "destination"
@class_prefix = "file_create_symlink"
@supported_formats = ["cf", "dsc"]
file state create_symlink_force(source) {}

@class_parameter_index = 1
@class_parameter_name = "destination"
@class_prefix = "file_download"
@supported_formats = ["cf", "dsc"]
file state download(source) {}

@class_parameter_index = 0
@class_parameter_name = "file"
@class_prefix = "file_ensure_lines_present"
@supported_formats = ["cf", "dsc"]
file state enforce_content(lines, enforce) {}

@class_parameter_index = 0
@class_parameter_name = "file"
@class_prefix = "file_ensure_block_in_section"
@supported_formats = ["cf", "dsc"]
file state ensure_block_in_section(section_start, section_end, block) {}

@class_parameter_index = 0
@class_parameter_name = "file"
@class_prefix = "file_ensure_block_present"
@supported_formats = ["cf", "dsc"]
file state ensure_block_present(block) {}

@class_parameter_index = 0
@class_parameter_name = "file"
@class_prefix = "file_ensure_key_value"
@supported_formats = ["cf", "dsc"]
file state ensure_key_value(key, value, separator) {}

@class_parameter_index = 0
@class_parameter_name = "file"
@class_prefix = "file_ensure_key_value"
@supported_formats = ["cf", "dsc"]
file state ensure_key_value_option(key, value, option, separator) {}

@class_parameter_index = 0
@class_parameter_name = "file"
@class_prefix = "file_ensure_key_value_parameter_in_list"
@supported_formats = ["cf", "dsc"]
file state ensure_key_value_parameter_in_list(key, key_value_separator, parameter, parameter_separator, leading_char_separator, closing_char_separator) {}

@class_parameter_index = 0
@class_parameter_name = "file"
@class_prefix = "file_ensure_key_value_parameter_not_in_list"
@supported_formats = ["cf", "dsc"]
file state ensure_key_value_parameter_not_in_list(key, key_value_separator, parameter_regex, parameter_separator, leading_char_separator, closing_char_separator) {}

@class_parameter_index = 0
@class_parameter_name = "file"
@class_prefix = "file_ensure_key_value_present_in_ini_section"
@supported_formats = ["cf", "dsc"]
file state ensure_key_value_present_in_ini_section(section, name, value) {}

@class_parameter_index = 0
@class_parameter_name = "file"
@class_prefix = "file_ensure_keys_values"
@supported_formats = ["cf", "dsc"]
file state ensure_keys_values(keys, separator) {}

@class_parameter_index = 0
@class_parameter_name = "file"
@class_prefix = "file_ensure_line_present_in_ini_section"
@supported_formats = ["cf", "dsc"]
file state ensure_line_present_in_ini_section(section, line) {}

@class_parameter_index = 0
@class_parameter_name = "file"
@class_prefix = "file_ensure_line_present_in_xml_tag"
@supported_formats = ["cf", "dsc"]
file state ensure_line_present_in_xml_tag(tag, line) {}

@class_parameter_index = 0
@class_parameter_name = "file"
@class_prefix = "file_ensure_lines_absent"
@supported_formats = ["cf", "dsc"]
file state ensure_lines_absent(lines) {}

@class_parameter_index = 0
@class_parameter_name = "file"
@class_prefix = "file_ensure_lines_present"
@supported_formats = ["cf", "dsc"]
file state ensure_lines_present(lines) {}

@class_parameter_index = 1
@class_parameter_name = "destination"
@class_prefix = "file_from_http_server"
@supported_formats = ["cf", "dsc"]
file state from_http_server(source) {}

@class_parameter_index = 1
@class_parameter_name = "destination"
@class_prefix = "file_from_local_source"
@supported_formats = ["cf", "dsc"]
file state from_local_source(source) {}

@class_parameter_index = 1
@class_parameter_name = "destination"
@class_prefix = "file_from_local_source"
@supported_formats = ["cf", "dsc"]
file state from_local_source_recursion(source, recursion) {}

@class_parameter_index = 1
@class_parameter_name = "destination"
@class_prefix = "file_from_local_source_with_check"
@supported_formats = ["cf", "dsc"]
file state from_local_source_with_check(source, check_command, rc_ok) {}

@class_parameter_index = 1
@class_parameter_name = "destination"
@class_prefix = "file_from_remote_source"
@supported_formats = ["cf", "dsc"]
file state from_remote_source(source) {}

@class_parameter_index = 1
@class_parameter_name = "destination"
@class_prefix = "file_from_remote_source"
@supported_formats = ["cf", "dsc"]
file state from_remote_source_recursion(source, recursion) {}

@class_parameter_index = 1
@class_parameter_name = "destination"
@class_prefix = "file_from_shared_folder"
@supported_formats = ["cf", "dsc"]
file state from_shared_folder(source, hash_type) {}

@class_parameter_index = 1
@class_parameter_name = "destination"
@class_prefix = "file_from_string_mustache"
@supported_formats = ["cf", "dsc"]
file state from_string_mustache(template) {}

@class_parameter_index = 1
@class_parameter_name = "destination"
@class_prefix = "file_from_template"
@supported_formats = ["cf", "dsc"]
file state from_template(source_template) {}

@class_parameter_index = 1
@class_parameter_name = "destination"
@class_prefix = "file_from_template"
@supported_formats = ["cf", "dsc"]
file state from_template_jinja2(source_template) {}

@class_parameter_index = 1
@class_parameter_name = "destination"
@class_prefix = "file_from_template"
@supported_formats = ["cf", "dsc"]
file state from_template_mustache(source_template) {}

@class_parameter_index = 1
@class_parameter_name = "destination"
@class_prefix = "file_from_template"
@supported_formats = ["cf", "dsc"]
file state from_template_type(source_template, template_type) {}

@class_parameter_index = 0
@class_parameter_name = "file"
@class_prefix = "file_key_value_parameter_absent_in_list"
@supported_formats = ["cf", "dsc"]
file state key_value_parameter_absent_in_list(key, key_value_separator, parameter_regex, parameter_separator, leading_char_separator, closing_char_separator) {}

@class_parameter_index = 0
@class_parameter_name = "file"
@class_prefix = "file_key_value_parameter_present_in_list"
@supported_formats = ["cf", "dsc"]
file state key_value_parameter_present_in_list(key, key_value_separator, parameter, parameter_separator, leading_char_separator, closing_char_separator) {}

@class_parameter_index = 0
@class_parameter_name = "file"
@class_prefix = "file_key_value_present"
@supported_formats = ["cf", "dsc"]
file state key_value_present(key, value, separator) {}

@class_parameter_index = 0
@class_parameter_name = "file"
@class_prefix = "file_key_value_present_in_ini_section"
@supported_formats = ["cf", "dsc"]
file state key_value_present_in_ini_section(section, name, value) {}

@class_parameter_index = 0
@class_parameter_name = "file"
@class_prefix = "file_key_value_present"
@supported_formats = ["cf", "dsc"]
file state key_value_present_option(key, value, separator, option) {}

@class_parameter_index = 0
@class_parameter_name = "file"
@class_prefix = "file_keys_values_present"
@supported_formats = ["cf", "dsc"]
file state keys_values_present(keys, separator) {}

@class_parameter_index = 0
@class_parameter_name = "file"
@class_prefix = "file_line_present_in_ini_section"
@supported_formats = ["cf", "dsc"]
file state line_present_in_ini_section(section, line) {}

@class_parameter_index = 0
@class_parameter_name = "file"
@class_prefix = "file_line_present_in_xml_tag"
@supported_formats = ["cf", "dsc"]
file state line_present_in_xml_tag(tag, line) {}

@class_parameter_index = 0
@class_parameter_name = "file"
@class_prefix = "file_lines_absent"
@supported_formats = ["cf", "dsc"]
file state lines_absent(lines) {}

@class_parameter_index = 0
@class_parameter_name = "file"
@class_prefix = "file_lines_present"
@supported_formats = ["cf", "dsc"]
file state lines_present(lines) {}

@class_parameter_index = 0
@class_parameter_name = "target"
@class_prefix = "file_present"
@supported_formats = ["cf", "dsc"]
file state present() {}

@class_parameter_index = 0
@class_parameter_name = "target"
@class_prefix = "file_remove"
@supported_formats = ["cf", "dsc"]
file state remove() {}

@class_parameter_index = 0
@class_parameter_name = "file"
@class_prefix = "file_replace_lines"
@supported_formats = ["cf", "dsc"]
file state replace_lines(line, replacement) {}

@class_parameter_index = 0
@class_parameter_name = "target"
@class_prefix = "file_report_content"
@supported_formats = ["cf", "dsc"]
file state report_content(regex, context) {}

@class_parameter_index = 0
@class_parameter_name = "target"
@class_prefix = "file_report_content_head"
@supported_formats = ["cf", "dsc"]
file state report_content_head(limit) {}

@class_parameter_index = 0
@class_parameter_name = "target"
@class_prefix = "file_report_content_tail"
@supported_formats = ["cf", "dsc"]
file state report_content_tail(limit) {}

@class_parameter_index = 1
@class_parameter_name = "destination"
@class_prefix = "file_symlink_present"
@supported_formats = ["cf", "dsc"]
file state symlink_present(source) {}

@class_parameter_index = 1
@class_parameter_name = "destination"
@class_prefix = "file_symlink_present"
@supported_formats = ["cf", "dsc"]
file state symlink_present_force(source) {}

@class_parameter_index = 1
@class_parameter_name = "destination"
@class_prefix = "file_symlink_present"
@supported_formats = ["cf", "dsc"]
file state symlink_present_option(source, enforce) {}

@class_parameter_index = 1
@class_parameter_name = "target_file"
@class_prefix = "file_template_expand"
@supported_formats = ["cf", "dsc"]
file state template_expand(tml_file, mode, owner, group) {}

@class_parameter_index = 0
@class_parameter_name = "group"
@class_prefix = "group_absent"
@supported_formats = ["cf", "dsc"]
group state absent() {}

@class_parameter_index = 0
@class_parameter_name = "group"
@class_prefix = "group_present"
@supported_formats = ["cf", "dsc"]
group state present() {}

@class_parameter_index = 1
@class_parameter_name = "url"
@class_prefix = "http_request_check_status_headers"
@supported_formats = ["cf", "dsc"]
http_request state check_status_headers(method, expected_status, headers) {}

@class_parameter_index = 1
@class_parameter_name = "url"
@class_prefix = "http_request_content_headers"
@supported_formats = ["cf", "dsc"]
http_request state content_headers(method, content, headers) {}

@class_parameter_index = 0
@class_parameter_name = "module_name"
@class_prefix = "kernel_module_configuration"
@supported_formats = ["cf", "dsc"]
kernel_module state configuration(configuration) {}

@class_parameter_index = 0
@class_parameter_name = "module_name"
@class_prefix = "kernel_module_enabled_at_boot"
@supported_formats = ["cf", "dsc"]
kernel_module state enabled_at_boot() {}

@class_parameter_index = 0
@class_parameter_name = "module_name"
@class_prefix = "kernel_module_loaded"
@supported_formats = ["cf", "dsc"]
kernel_module state loaded() {}

@class_parameter_index = 0
@class_parameter_name = "module_name"
@class_prefix = "kernel_module_not_loaded"
@supported_formats = ["cf", "dsc"]
kernel_module state not_loaded() {}

@class_parameter_index = 0
@class_parameter_name = "key"
@class_prefix = "monitoring_parameter"
@supported_formats = ["cf", "dsc"]
monitoring state parameter(value) {}

@class_parameter_index = 0
@class_parameter_name = "template"
@class_prefix = "monitoring_template"
@supported_formats = ["cf", "dsc"]
monitoring state template() {}

@class_parameter_index = 0
@class_parameter_name = "name"
@class_prefix = "package_absent"
@supported_formats = ["cf", "dsc"]
package state absent(version, architecture, provider) {}

@class_parameter_index = 0
@class_parameter_name = "package_name"
@class_prefix = "package_check_installed"
@supported_formats = ["cf", "dsc"]
package state check_installed() {}

@class_parameter_index = 0
@class_parameter_name = "package_name"
@class_prefix = "package_install"
@supported_formats = ["cf", "dsc"]
package state install() {}

@class_parameter_index = 0
@class_parameter_name = "package_name"
@class_prefix = "package_install"
@supported_formats = ["cf", "dsc"]
package state install_version(package_version) {}

@class_parameter_index = 0
@class_parameter_name = "package_name"
@class_prefix = "package_install"
@supported_formats = ["cf", "dsc"]
package state install_version_cmp(version_comparator, package_version, action) {}

@class_parameter_index = 0
@class_parameter_name = "package_name"
@class_prefix = "package_install"
@supported_formats = ["cf", "dsc"]
package state install_version_cmp_update(version_comparator, package_version, action, update_policy) {}

@class_parameter_index = 0
@class_parameter_name = "name"
@class_prefix = "package_present"
@supported_formats = ["cf", "dsc"]
package state present(version, architecture, provider) {}

@class_parameter_index = 0
@class_parameter_name = "package_name"
@class_prefix = "package_remove"
@supported_formats = ["cf", "dsc"]
package state remove() {}

@class_parameter_index = 0
@class_parameter_name = "name"
@class_prefix = "package_state"
@supported_formats = ["cf", "dsc"]
package state state(version, architecture, provider, state) {}

@class_parameter_index = 0
@class_parameter_name = "name"
@class_prefix = "package_state_options"
@supported_formats = ["cf", "dsc"]
package state state_options(version, architecture, provider, state, options) {}

@class_parameter_index = 0
@class_parameter_name = "package_name"
@class_prefix = "package_install"
@supported_formats = ["cf", "dsc"]
package state verify() {}

@class_parameter_index = 0
@class_parameter_name = "package_name"
@class_prefix = "package_install"
@supported_formats = ["cf", "dsc"]
package state verify_version(package_version) {}

@class_parameter_index = 0
@class_parameter_name = "path"
@class_prefix = "permissions_acl_entry"
@supported_formats = ["cf", "dsc"]
permissions state acl_entry(recursive, user, group, other) {}

@class_parameter_index = 0
@class_parameter_name = "path"
@class_prefix = "permissions"
@supported_formats = ["cf", "dsc"]
permissions state dirs(mode, owner, group) {}

@class_parameter_index = 0
@class_parameter_name = "path"
@class_prefix = "permissions"
@supported_formats = ["cf", "dsc"]
permissions state dirs_recurse(mode, owner, group) {}

@class_parameter_index = 0
@class_parameter_name = "path"
@class_prefix = "permissions"
@supported_formats = ["cf", "dsc"]
permissions state dirs_recursive(mode, owner, group) {}

@class_parameter_index = 0
@class_parameter_name = "path"
@class_prefix = "permissions_group_acl_absent"
@supported_formats = ["cf", "dsc"]
permissions state group_acl_absent(recursive, group) {}

@class_parameter_index = 0
@class_parameter_name = "path"
@class_prefix = "permissions_group_acl_present"
@supported_formats = ["cf", "dsc"]
permissions state group_acl_present(recursive, group, ace) {}

@class_parameter_index = 0
@class_parameter_name = "path"
@class_prefix = "permissions_other_acl_present"
@supported_formats = ["cf", "dsc"]
permissions state other_acl_present(recursive, other) {}

@class_parameter_index = 0
@class_parameter_name = "path"
@class_prefix = "permissions_posix_acls_absent"
@supported_formats = ["cf", "dsc"]
permissions state posix_acls_absent(recursive) {}

@class_parameter_index = 0
@class_parameter_name = "path"
@class_prefix = "permissions"
@supported_formats = ["cf", "dsc"]
permissions state recurse(mode, owner, group) {}

@class_parameter_index = 0
@class_parameter_name = "path"
@class_prefix = "permissions"
@supported_formats = ["cf", "dsc"]
permissions state recursive(mode, owner, group) {}

@class_parameter_index = 0
@class_parameter_name = "path"
@class_prefix = "permissions"
@supported_formats = ["cf", "dsc"]
permissions state type_recursion(mode, owner, group, type, recursion) {}

@class_parameter_index = 0
@class_parameter_name = "path"
@class_prefix = "permissions_user_acl_absent"
@supported_formats = ["cf", "dsc"]
permissions state user_acl_absent(recursive, user) {}

@class_parameter_index = 0
@class_parameter_name = "path"
@class_prefix = "permissions_user_acl_present"
@supported_formats = ["cf", "dsc"]
permissions state user_acl_present(recursive, user, ace) {}

@class_parameter_index = 0
@class_parameter_name = "job_id"
@class_prefix = "schedule_simple"
@supported_formats = ["cf", "dsc"]
schedule state simple(agent_periodicity, max_execution_delay_minutes, max_execution_delay_hours, start_on_minutes, start_on_hours, start_on_day_of_week, periodicity_minutes, periodicity_hours, periodicity_days, mode) {}

@class_parameter_index = 0
@class_parameter_name = "job_id"
@class_prefix = "schedule_simple"
@supported_formats = ["cf", "dsc"]
schedule state simple_catchup(agent_periodicity, max_execution_delay_minutes, max_execution_delay_hours, start_on_minutes, start_on_hours, start_on_day_of_week, periodicity_minutes, periodicity_hours, periodicity_days) {}

@class_parameter_index = 0
@class_parameter_name = "job_id"
@class_prefix = "schedule_simple"
@supported_formats = ["cf", "dsc"]
schedule state simple_nodups(agent_periodicity, max_execution_delay_minutes, max_execution_delay_hours, start_on_minutes, start_on_hours, start_on_day_of_week, periodicity_minutes, periodicity_hours, periodicity_days) {}

@class_parameter_index = 0
@class_parameter_name = "job_id"
@class_prefix = "schedule_simple"
@supported_formats = ["cf", "dsc"]
schedule state simple_stateless(agent_periodicity, max_execution_delay_minutes, max_execution_delay_hours, start_on_minutes, start_on_hours, start_on_day_of_week, periodicity_minutes, periodicity_hours, periodicity_days) {}

@class_parameter_index = 0
@class_parameter_name = "service_name"
@class_prefix = "service_action"
@supported_formats = ["cf", "dsc"]
service state action(action) {}

@class_parameter_index = 0
@class_parameter_name = "service_name"
@class_prefix = "service_check_disabled_at_boot"
@supported_formats = ["cf", "dsc"]
service state check_disabled_at_boot() {}

@class_parameter_index = 0
@class_parameter_name = "service_name"
@class_prefix = "service_check_running"
@supported_formats = ["cf", "dsc"]
service state check_running() {}

@class_parameter_index = 0
@class_parameter_name = "service_regex"
@class_prefix = "service_check_running"
@supported_formats = ["cf", "dsc"]
service state check_running_ps() {}

@class_parameter_index = 0
@class_parameter_name = "service_name"
@class_prefix = "service_check_started_at_boot"
@supported_formats = ["cf", "dsc"]
service state check_started_at_boot() {}

@class_parameter_index = 0
@class_parameter_name = "service_name"
@class_prefix = "service_disabled"
@supported_formats = ["cf", "dsc"]
service state disabled() {}

@class_parameter_index = 0
@class_parameter_name = "service_name"
@class_prefix = "service_enabled"
@supported_formats = ["cf", "dsc"]
service state enabled() {}

@class_parameter_index = 0
@class_parameter_name = "service_name"
@class_prefix = "service_ensure_disabled_at_boot"
@supported_formats = ["cf", "dsc"]
service state ensure_disabled_at_boot() {}

@class_parameter_index = 0
@class_parameter_name = "service_name"
@class_prefix = "service_ensure_running"
@supported_formats = ["cf", "dsc"]
service state ensure_running() {}

@class_parameter_index = 0
@class_parameter_name = "service_name"
@class_prefix = "service_ensure_running"
@supported_formats = ["cf", "dsc"]
service state ensure_running_path(service_path) {}

@class_parameter_index = 0
@class_parameter_name = "service_name"
@class_prefix = "service_ensure_started_at_boot"
@supported_formats = ["cf", "dsc"]
service state ensure_started_at_boot() {}

@class_parameter_index = 0
@class_parameter_name = "service_name"
@class_prefix = "service_ensure_stopped"
@supported_formats = ["cf", "dsc"]
service state ensure_stopped() {}

@class_parameter_index = 0
@class_parameter_name = "service_name"
@class_prefix = "service_reload"
@supported_formats = ["cf", "dsc"]
service state reload() {}

@class_parameter_index = 0
@class_parameter_name = "service_name"
@class_prefix = "service_restart"
@supported_formats = ["cf", "dsc"]
service state restart() {}

@class_parameter_index = 0
@class_parameter_name = "service_name"
@class_prefix = "service_restart"
@supported_formats = ["cf", "dsc"]
service state restart_if(trigger_class) {}

@class_parameter_index = 0
@class_parameter_name = "service_name"
@class_prefix = "service_start"
@supported_formats = ["cf", "dsc"]
service state start() {}

@class_parameter_index = 0
@class_parameter_name = "service_name"
@class_prefix = "service_started"
@supported_formats = ["cf", "dsc"]
service state started() {}

@class_parameter_index = 0
@class_parameter_name = "service_name"
@class_prefix = "service_started"
@supported_formats = ["cf", "dsc"]
service state started_path(service_path) {}

@class_parameter_index = 0
@class_parameter_name = "service_name"
@class_prefix = "service_stop"
@supported_formats = ["cf", "dsc"]
service state stop() {}

@class_parameter_index = 0
@class_parameter_name = "service_name"
@class_prefix = "service_stopped"
@supported_formats = ["cf", "dsc"]
service state stopped() {}

@class_parameter_index = 1
@class_parameter_name = "file_id"
@class_prefix = "sharedfile_from_node"
@supported_formats = ["cf", "dsc"]
sharedfile state from_node(source_uuid, file_path) {}

@class_parameter_index = 1
@class_parameter_name = "file_id"
@class_prefix = "sharedfile_to_node"
@supported_formats = ["cf", "dsc"]
sharedfile state to_node(target_uuid, file_path, ttl) {}

@class_parameter_index = 0
@class_parameter_name = "key"
@class_prefix = "sysctl_value"
@supported_formats = ["cf", "dsc"]
sysctl state value(value, filename, option) {}

@class_parameter_index = 0
@class_parameter_name = "login"
@class_prefix = "user_absent"
@supported_formats = ["cf", "dsc"]
user state absent() {}

@class_parameter_index = 0
@class_parameter_name = "login"
@class_prefix = "user_create"
@supported_formats = ["cf", "dsc"]
user state create(description, home, group, shell, locked) {}

@class_parameter_index = 0
@class_parameter_name = "login"
@class_prefix = "user_fullname"
@supported_formats = ["cf", "dsc"]
user state fullname(fullname) {}

@class_parameter_index = 0
@class_parameter_name = "user"
@class_prefix = "user_group"
@supported_formats = ["cf", "dsc"]
user state group(group_name) {}

@class_parameter_index = 0
@class_parameter_name = "login"
@class_prefix = "user_home"
@supported_formats = ["cf", "dsc"]
user state home(home) {}

@class_parameter_index = 0
@class_parameter_name = "login"
@class_prefix = "user_locked"
@supported_formats = ["cf", "dsc"]
user state locked() {}

@class_parameter_index = 0
@class_parameter_name = "login"
@class_prefix = "user_password_hash"
@supported_formats = ["cf", "dsc"]
user state password_hash(password) {}

@class_parameter_index = 0
@class_parameter_name = "login"
@class_prefix = "user_present"
@supported_formats = ["cf", "dsc"]
user state present() {}

@class_parameter_index = 0
@class_parameter_name = "login"
@class_prefix = "user_primary_group"
@supported_formats = ["cf", "dsc"]
user state primary_group(primary_group) {}

@class_parameter_index = 0
@class_parameter_name = "login"
@class_prefix = "user_shell"
@supported_formats = ["cf", "dsc"]
user state shell(shell) {}

@class_parameter_index = 0
@class_parameter_name = "login"
@class_prefix = "user_uid"
@supported_formats = ["cf", "dsc"]
user state uid(uid) {}

@class_parameter_index = 1
@class_parameter_name = "variable_name"
@class_prefix = "variable_dict"
@supported_formats = ["cf", "dsc"]
variable state dict(variable_prefix, value) {}

@class_parameter_index = 1
@class_parameter_name = "variable_name"
@class_prefix = "variable_dict_from_file"
@supported_formats = ["cf", "dsc"]
variable state dict_from_file(variable_prefix, file_name) {}

@class_parameter_index = 1
@class_parameter_name = "variable_name"
@class_prefix = "variable_dict_from_file_type"
@supported_formats = ["cf", "dsc"]
variable state dict_from_file_type(variable_prefix, file_name, file_type) {}

@class_parameter_index = 1
@class_parameter_name = "variable_name"
@class_prefix = "variable_dict_from_osquery"
@supported_formats = ["cf", "dsc"]
variable state dict_from_osquery(variable_prefix, query) {}

@class_parameter_index = 1
@class_parameter_name = "variable_name"
@class_prefix = "variable_dict_merge"
@supported_formats = ["cf", "dsc"]
variable state dict_merge(variable_prefix, first_variable, second_variable) {}

@class_parameter_index = 1
@class_parameter_name = "variable_name"
@class_prefix = "variable_dict_merge_tolerant"
@supported_formats = ["cf", "dsc"]
variable state dict_merge_tolerant(variable_prefix, first_variable, second_variable) {}

@class_parameter_index = 1
@class_parameter_name = "variable_name"
@class_prefix = "variable_iterator"
@supported_formats = ["cf", "dsc"]
variable state iterator(variable_prefix, value, separator) {}

@class_parameter_index = 1
@class_parameter_name = "variable_name"
@class_prefix = "variable_iterator_from_file"
@supported_formats = ["cf", "dsc"]
variable state iterator_from_file(variable_prefix, file_name, separator_regex, comments_regex) {}

@class_parameter_index = 1
@class_parameter_name = "variable_name"
@class_prefix = "variable_string"
@supported_formats = ["cf", "dsc"]
variable state string(variable_prefix, value) {}

@class_parameter_index = 1
@class_parameter_name = "variable_name"
@class_prefix = "variable_string_default"
@supported_formats = ["cf", "dsc"]
variable state string_default(variable_prefix, source_variable, default_value) {}

@class_parameter_index = 1
@class_parameter_name = "variable_name"
@class_prefix = "variable_string_from_augeas"
@supported_formats = ["cf", "dsc"]
variable state string_from_augeas(variable_prefix, path, lens, file) {}

@class_parameter_index = 1
@class_parameter_name = "variable_name"
@class_prefix = "variable_string_from_command"
@supported_formats = ["cf", "dsc"]
variable state string_from_command(variable_prefix, command) {}

@class_parameter_index = 1
@class_parameter_name = "variable_name"
@class_prefix = "variable_string_from_file"
@supported_formats = ["cf", "dsc"]
variable state string_from_file(variable_prefix, file_name) {}

@class_parameter_index = 1
@class_parameter_name = "variable_name"
@class_prefix = "variable_string_from_math_expression"
@supported_formats = ["cf", "dsc"]
variable state string_from_math_expression(variable_prefix, expression, format) {}

@class_parameter_index = 0
@class_parameter_name = "variable_name"
@class_prefix = "variable_string_match"
@supported_formats = ["cf", "dsc"]
variable state string_match(expected_match) {}


