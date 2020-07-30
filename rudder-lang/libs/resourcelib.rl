@format=0

resource file(p0)
resource service(p0)
resource user(p0)
resource permissions(p0)
resource group(p0)
resource condition(p0)
resource sysctl(p0)
resource monitoring(p0)
resource package(p0)
resource directory(p0)
resource sharedfile(p0, p1)
resource environment(p0)
resource http_request(p0, p1)
resource variable(p0, p1)
resource command(p0)
resource kernel_module(p0)
resource schedule(p0)

@class_parameter = {"command"= 0}
@supported_formats  = ["cf", "dsc"]
command state execution() {}

@class_parameter = {"command"= 0}
@supported_formats  = ["cf", "dsc"]
command state execution_once(ok_codes, until, unique_id) {}

@class_parameter = {"command"= 0}
@supported_formats  = ["cf", "dsc"]
command state execution_result(kept_codes, repaired_codes) {}

@class_parameter = {"condition_prefix"= 0}
@supported_formats  = ["cf", "dsc"]
condition state from_command(command, true_codes, false_codes) {}

@class_parameter = {"condition_prefix"= 0}
@supported_formats  = ["cf", "dsc"]
condition state from_expression(condition_expression) {}

@class_parameter = {"condition_prefix"= 0}
@supported_formats  = ["cf", "dsc"]
condition state from_expression_persistent(condition_expression, duration) {}

@class_parameter = {"condition_prefix"= 0}
@supported_formats  = ["cf", "dsc"]
condition state from_variable_existence(variable_name) {}

@class_parameter = {"condition_prefix"= 0}
@supported_formats  = ["cf", "dsc"]
condition state from_variable_match(variable_name, expected_match) {}

@class_parameter = {"condition"= 0}
@supported_formats  = ["cf", "dsc"]
condition state once() {}

@class_parameter = {"target"= 0}
@supported_formats  = ["cf", "dsc"]
directory state absent(recursive) {}

@class_parameter = {"directory_name"= 0}
@supported_formats  = ["cf", "dsc"]
directory state check_exists() {}

@class_parameter = {"target"= 0}
@supported_formats  = ["cf", "dsc"]
directory state create() {}

@class_parameter = {"target"= 0}
@supported_formats  = ["cf", "dsc"]
directory state present() {}

@class_parameter = {"name"= 0}
@supported_formats  = ["cf", "dsc"]
environment state variable_present(value) {}

@class_parameter = {"target"= 0}
@supported_formats  = ["cf", "dsc"]
file state absent() {}

@class_parameter = {"variable_name"= 1}
@supported_formats  = ["cf", "dsc"]
file state augeas_commands(variable_prefix, commands, autoload) {}

@class_parameter = {"lens"= 2}
@supported_formats  = ["cf", "dsc"]
file state augeas_set(path, value, file) {}

@class_parameter = {"file"= 0}
@supported_formats  = ["cf", "dsc"]
file state block_present(block) {}

@class_parameter = {"file"= 0}
@supported_formats  = ["cf", "dsc"]
file state block_present_in_section(section_start, section_end, block) {}

@class_parameter = {"file_name"= 0}
@supported_formats  = ["cf", "dsc"]
file state check_block_device() {}

@class_parameter = {"file_name"= 0}
@supported_formats  = ["cf", "dsc"]
file state check_character_device() {}

@class_parameter = {"file_name"= 0}
@supported_formats  = ["cf", "dsc"]
file state check_exists() {}

@class_parameter = {"file_name"= 0}
@supported_formats  = ["cf", "dsc"]
file state check_FIFO_pipe() {}

@class_parameter = {"file_name_1"= 0}
@supported_formats  = ["cf", "dsc"]
file state check_hardlink(file_name_2) {}

@class_parameter = {"file_name"= 0}
@supported_formats  = ["cf", "dsc"]
file state check_regular() {}

@class_parameter = {"file_name"= 0}
@supported_formats  = ["cf", "dsc"]
file state check_socket() {}

@class_parameter = {"file_name"= 0}
@supported_formats  = ["cf", "dsc"]
file state check_symlink() {}

@class_parameter = {"symlink"= 0}
@supported_formats  = ["cf", "dsc"]
file state check_symlinkto(target) {}

@class_parameter = {"file"= 0}
@supported_formats  = ["cf", "dsc"]
file state content(lines, enforce) {}

@class_parameter = {"destination"= 1}
@supported_formats  = ["cf", "dsc"]
file state copy_from_local_source(source) {}

@class_parameter = {"destination"= 1}
@supported_formats  = ["cf", "dsc"]
file state copy_from_local_source_recursion(source, recursion) {}

@class_parameter = {"destination"= 1}
@supported_formats  = ["cf", "dsc"]
file state copy_from_local_source_with_check(source, check_command, rc_ok) {}

@class_parameter = {"destination"= 1}
@supported_formats  = ["cf", "dsc"]
file state copy_from_remote_source(source) {}

@class_parameter = {"destination"= 1}
@supported_formats  = ["cf", "dsc"]
file state copy_from_remote_source_recursion(source, recursion) {}

@class_parameter = {"target"= 0}
@supported_formats  = ["cf", "dsc"]
file state create() {}

@class_parameter = {"destination"= 1}
@supported_formats  = ["cf", "dsc"]
file state create_symlink(source) {}

@class_parameter = {"destination"= 1}
@supported_formats  = ["cf", "dsc"]
file state create_symlink_enforce(source, enforce) {}

@class_parameter = {"destination"= 1}
@supported_formats  = ["cf", "dsc"]
file state create_symlink_force(source) {}

@class_parameter = {"destination"= 1}
@supported_formats  = ["cf", "dsc"]
file state download(source) {}

@class_parameter = {"file"= 0}
@supported_formats  = ["cf", "dsc"]
file state enforce_content(lines, enforce) {}

@class_parameter = {"file"= 0}
@supported_formats  = ["cf", "dsc"]
file state ensure_block_in_section(section_start, section_end, block) {}

@class_parameter = {"file"= 0}
@supported_formats  = ["cf", "dsc"]
file state ensure_block_present(block) {}

@class_parameter = {"file"= 0}
@supported_formats  = ["cf", "dsc"]
file state ensure_key_value(key, value, separator) {}

@class_parameter = {"file"= 0}
@supported_formats  = ["cf", "dsc"]
file state ensure_key_value_option(key, value, option, separator) {}

@class_parameter = {"file"= 0}
@supported_formats  = ["cf", "dsc"]
file state ensure_key_value_parameter_in_list(key, key_value_separator, parameter, parameter_separator, leading_char_separator, closing_char_separator) {}

@class_parameter = {"file"= 0}
@supported_formats  = ["cf", "dsc"]
file state ensure_key_value_parameter_not_in_list(key, key_value_separator, parameter_regex, parameter_separator, leading_char_separator, closing_char_separator) {}

@class_parameter = {"file"= 0}
@supported_formats  = ["cf", "dsc"]
file state ensure_key_value_present_in_ini_section(section, name, value) {}

@class_parameter = {"file"= 0}
@supported_formats  = ["cf", "dsc"]
file state ensure_keys_values(keys, separator) {}

@class_parameter = {"file"= 0}
@supported_formats  = ["cf", "dsc"]
file state ensure_line_present_in_ini_section(section, line) {}

@class_parameter = {"file"= 0}
@supported_formats  = ["cf", "dsc"]
file state ensure_line_present_in_xml_tag(tag, line) {}

@class_parameter = {"file"= 0}
@supported_formats  = ["cf", "dsc"]
file state ensure_lines_absent(lines) {}

@class_parameter = {"file"= 0}
@supported_formats  = ["cf", "dsc"]
file state ensure_lines_present(lines) {}

@class_parameter = {"destination"= 1}
@supported_formats  = ["cf", "dsc"]
file state from_http_server(source) {}

@class_parameter = {"destination"= 1}
@supported_formats  = ["cf", "dsc"]
file state from_local_source(source) {}

@class_parameter = {"destination"= 1}
@supported_formats  = ["cf", "dsc"]
file state from_local_source_recursion(source, recursion) {}

@class_parameter = {"destination"= 1}
@supported_formats  = ["cf", "dsc"]
file state from_local_source_with_check(source, check_command, rc_ok) {}

@class_parameter = {"destination"= 1}
@supported_formats  = ["cf", "dsc"]
file state from_remote_source(source) {}

@class_parameter = {"destination"= 1}
@supported_formats  = ["cf", "dsc"]
file state from_remote_source_recursion(source, recursion) {}

@class_parameter = {"destination"= 1}
@supported_formats  = ["cf", "dsc"]
file state from_shared_folder(source, hash_type) {}

@class_parameter = {"destination"= 1}
@supported_formats  = ["cf", "dsc"]
file state from_string_mustache(template) {}

@class_parameter = {"destination"= 1}
@supported_formats  = ["cf", "dsc"]
file state from_template(source_template) {}

@class_parameter = {"destination"= 1}
@supported_formats  = ["cf", "dsc"]
file state from_template_jinja2(source_template) {}

@class_parameter = {"destination"= 1}
@supported_formats  = ["cf", "dsc"]
file state from_template_mustache(source_template) {}

@class_parameter = {"destination"= 1}
@supported_formats  = ["cf", "dsc"]
file state from_template_type(source_template, template_type) {}

@class_parameter = {"file"= 0}
@supported_formats  = ["cf", "dsc"]
file state key_value_parameter_absent_in_list(key, key_value_separator, parameter_regex, parameter_separator, leading_char_separator, closing_char_separator) {}

@class_parameter = {"file"= 0}
@supported_formats  = ["cf", "dsc"]
file state key_value_parameter_present_in_list(key, key_value_separator, parameter, parameter_separator, leading_char_separator, closing_char_separator) {}

@class_parameter = {"file"= 0}
@supported_formats  = ["cf", "dsc"]
file state key_value_present(key, value, separator) {}

@class_parameter = {"file"= 0}
@supported_formats  = ["cf", "dsc"]
file state key_value_present_in_ini_section(section, name, value) {}

@class_parameter = {"file"= 0}
@supported_formats  = ["cf", "dsc"]
file state key_value_present_option(key, value, separator, option) {}

@class_parameter = {"file"= 0}
@supported_formats  = ["cf", "dsc"]
file state keys_values_present(keys, separator) {}

@class_parameter = {"file"= 0}
@supported_formats  = ["cf", "dsc"]
file state line_present_in_ini_section(section, line) {}

@class_parameter = {"file"= 0}
@supported_formats  = ["cf", "dsc"]
file state line_present_in_xml_tag(tag, line) {}

@class_parameter = {"file"= 0}
@supported_formats  = ["cf", "dsc"]
file state lines_absent(lines) {}

@class_parameter = {"file"= 0}
@supported_formats  = ["cf", "dsc"]
file state lines_present(lines) {}

@class_parameter = {"target"= 0}
@supported_formats  = ["cf", "dsc"]
file state present() {}

@class_parameter = {"target"= 0}
@supported_formats  = ["cf", "dsc"]
file state remove() {}

@class_parameter = {"file"= 0}
@supported_formats  = ["cf", "dsc"]
file state replace_lines(line, replacement) {}

@class_parameter = {"target"= 0}
@supported_formats  = ["cf", "dsc"]
file state report_content(regex, context) {}

@class_parameter = {"target"= 0}
@supported_formats  = ["cf", "dsc"]
file state report_content_head(limit) {}

@class_parameter = {"target"= 0}
@supported_formats  = ["cf", "dsc"]
file state report_content_tail(limit) {}

@class_parameter = {"destination"= 1}
@supported_formats  = ["cf", "dsc"]
file state symlink_present(source) {}

@class_parameter = {"destination"= 1}
@supported_formats  = ["cf", "dsc"]
file state symlink_present_force(source) {}

@class_parameter = {"destination"= 1}
@supported_formats  = ["cf", "dsc"]
file state symlink_present_option(source, enforce) {}

@class_parameter = {"target_file"= 1}
@supported_formats  = ["cf", "dsc"]
file state template_expand(tml_file, mode, owner, group) {}

@class_parameter = {"group"= 0}
@supported_formats  = ["cf", "dsc"]
group state absent() {}

@class_parameter = {"group"= 0}
@supported_formats  = ["cf", "dsc"]
group state present() {}

@class_parameter = {"url"= 1}
@supported_formats  = ["cf", "dsc"]
http_request state check_status_headers(method, expected_status, headers) {}

@class_parameter = {"url"= 1}
@supported_formats  = ["cf", "dsc"]
http_request state content_headers(method, content, headers) {}

@class_parameter = {"module_name"= 0}
@supported_formats  = ["cf", "dsc"]
kernel_module state configuration(configuration) {}

@class_parameter = {"module_name"= 0}
@supported_formats  = ["cf", "dsc"]
kernel_module state enabled_at_boot() {}

@class_parameter = {"module_name"= 0}
@supported_formats  = ["cf", "dsc"]
kernel_module state loaded() {}

@class_parameter = {"module_name"= 0}
@supported_formats  = ["cf", "dsc"]
kernel_module state not_loaded() {}

@class_parameter = {"key"= 0}
@supported_formats  = ["cf", "dsc"]
monitoring state parameter(value) {}

@class_parameter = {"template"= 0}
@supported_formats  = ["cf", "dsc"]
monitoring state template() {}

@class_parameter = {"name"= 0}
@supported_formats  = ["cf", "dsc"]
package state absent(version, architecture, provider) {}

@class_parameter = {"package_name"= 0}
@supported_formats  = ["cf", "dsc"]
package state check_installed() {}

@class_parameter = {"package_name"= 0}
@supported_formats  = ["cf", "dsc"]
package state install() {}

@class_parameter = {"package_name"= 0}
@supported_formats  = ["cf", "dsc"]
package state install_version(package_version) {}

@class_parameter = {"package_name"= 0}
@supported_formats  = ["cf", "dsc"]
package state install_version_cmp(version_comparator, package_version, action) {}

@class_parameter = {"package_name"= 0}
@supported_formats  = ["cf", "dsc"]
package state install_version_cmp_update(version_comparator, package_version, action, update_policy) {}

@class_parameter = {"name"= 0}
@supported_formats  = ["cf", "dsc"]
package state present(version, architecture, provider) {}

@class_parameter = {"package_name"= 0}
@supported_formats  = ["cf", "dsc"]
package state remove() {}

@class_parameter = {"name"= 0}
@supported_formats  = ["cf", "dsc"]
package state state(version, architecture, provider, state) {}

@class_parameter = {"name"= 0}
@supported_formats  = ["cf", "dsc"]
package state state_options(version, architecture, provider, state, options) {}

@class_parameter = {"package_name"= 0}
@supported_formats  = ["cf", "dsc"]
package state verify() {}

@class_parameter = {"package_name"= 0}
@supported_formats  = ["cf", "dsc"]
package state verify_version(package_version) {}

@class_parameter = {"path"= 0}
@supported_formats  = ["cf", "dsc"]
permissions state acl_entry(recursive, user, group, other) {}

@class_parameter = {"path"= 0}
@supported_formats  = ["cf", "dsc"]
permissions state dirs(mode, owner, group) {}

@class_parameter = {"path"= 0}
@supported_formats  = ["cf", "dsc"]
permissions state dirs_recurse(mode, owner, group) {}

@class_parameter = {"path"= 0}
@supported_formats  = ["cf", "dsc"]
permissions state dirs_recursive(mode, owner, group) {}

@class_parameter = {"path"= 0}
@supported_formats  = ["cf", "dsc"]
permissions state group_acl_absent(recursive, group) {}

@class_parameter = {"path"= 0}
@supported_formats  = ["cf", "dsc"]
permissions state group_acl_present(recursive, group, ace) {}

@class_parameter = {"path"= 0}
@supported_formats  = ["cf", "dsc"]
permissions state other_acl_present(recursive, other) {}

@class_parameter = {"path"= 0}
@supported_formats  = ["cf", "dsc"]
permissions state posix_acls_absent(recursive) {}

@class_parameter = {"path"= 0}
@supported_formats  = ["cf", "dsc"]
permissions state recurse(mode, owner, group) {}

@class_parameter = {"path"= 0}
@supported_formats  = ["cf", "dsc"]
permissions state recursive(mode, owner, group) {}

@class_parameter = {"path"= 0}
@supported_formats  = ["cf", "dsc"]
permissions state type_recursion(mode, owner, group, type, recursion) {}

@class_parameter = {"path"= 0}
@supported_formats  = ["cf", "dsc"]
permissions state user_acl_absent(recursive, user) {}

@class_parameter = {"path"= 0}
@supported_formats  = ["cf", "dsc"]
permissions state user_acl_present(recursive, user, ace) {}

@class_parameter = {"job_id"= 0}
@supported_formats  = ["cf", "dsc"]
schedule state simple(agent_periodicity, max_execution_delay_minutes, max_execution_delay_hours, start_on_minutes, start_on_hours, start_on_day_of_week, periodicity_minutes, periodicity_hours, periodicity_days, mode) {}

@class_parameter = {"job_id"= 0}
@supported_formats  = ["cf", "dsc"]
schedule state simple_catchup(agent_periodicity, max_execution_delay_minutes, max_execution_delay_hours, start_on_minutes, start_on_hours, start_on_day_of_week, periodicity_minutes, periodicity_hours, periodicity_days) {}

@class_parameter = {"job_id"= 0}
@supported_formats  = ["cf", "dsc"]
schedule state simple_nodups(agent_periodicity, max_execution_delay_minutes, max_execution_delay_hours, start_on_minutes, start_on_hours, start_on_day_of_week, periodicity_minutes, periodicity_hours, periodicity_days) {}

@class_parameter = {"job_id"= 0}
@supported_formats  = ["cf", "dsc"]
schedule state simple_stateless(agent_periodicity, max_execution_delay_minutes, max_execution_delay_hours, start_on_minutes, start_on_hours, start_on_day_of_week, periodicity_minutes, periodicity_hours, periodicity_days) {}

@class_parameter = {"service_name"= 0}
@supported_formats  = ["cf", "dsc"]
service state action(action) {}

@class_parameter = {"service_name"= 0}
@supported_formats  = ["cf", "dsc"]
service state check_disabled_at_boot() {}

@class_parameter = {"service_name"= 0}
@supported_formats  = ["cf", "dsc"]
service state check_running() {}

@class_parameter = {"service_regex"= 0}
@supported_formats  = ["cf", "dsc"]
service state check_running_ps() {}

@class_parameter = {"service_name"= 0}
@supported_formats  = ["cf", "dsc"]
service state check_started_at_boot() {}

@class_parameter = {"service_name"= 0}
@supported_formats  = ["cf", "dsc"]
service state disabled() {}

@class_parameter = {"service_name"= 0}
@supported_formats  = ["cf", "dsc"]
service state enabled() {}

@class_parameter = {"service_name"= 0}
@supported_formats  = ["cf", "dsc"]
service state ensure_disabled_at_boot() {}

@class_parameter = {"service_name"= 0}
@supported_formats  = ["cf", "dsc"]
service state ensure_running() {}

@class_parameter = {"service_name"= 0}
@supported_formats  = ["cf", "dsc"]
service state ensure_running_path(service_path) {}

@class_parameter = {"service_name"= 0}
@supported_formats  = ["cf", "dsc"]
service state ensure_started_at_boot() {}

@class_parameter = {"service_name"= 0}
@supported_formats  = ["cf", "dsc"]
service state ensure_stopped() {}

@class_parameter = {"service_name"= 0}
@supported_formats  = ["cf", "dsc"]
service state reload() {}

@class_parameter = {"service_name"= 0}
@supported_formats  = ["cf", "dsc"]
service state restart() {}

@class_parameter = {"service_name"= 0}
@supported_formats  = ["cf", "dsc"]
service state restart_if(trigger_class) {}

@class_parameter = {"service_name"= 0}
@supported_formats  = ["cf", "dsc"]
service state start() {}

@class_parameter = {"service_name"= 0}
@supported_formats  = ["cf", "dsc"]
service state started() {}

@class_parameter = {"service_name"= 0}
@supported_formats  = ["cf", "dsc"]
service state started_path(service_path) {}

@class_parameter = {"service_name"= 0}
@supported_formats  = ["cf", "dsc"]
service state stop() {}

@class_parameter = {"service_name"= 0}
@supported_formats  = ["cf", "dsc"]
service state stopped() {}

@class_parameter = {"file_id"= 1}
@supported_formats  = ["cf", "dsc"]
sharedfile state from_node(source_uuid, file_path) {}

@class_parameter = {"file_id"= 1}
@supported_formats  = ["cf", "dsc"]
sharedfile state to_node(target_uuid, file_path, ttl) {}

@class_parameter = {"key"= 0}
@supported_formats  = ["cf", "dsc"]
sysctl state value(value, filename, option) {}

@class_parameter = {"login"= 0}
@supported_formats  = ["cf", "dsc"]
user state absent() {}

@class_parameter = {"login"= 0}
@supported_formats  = ["cf", "dsc"]
user state create(description, home, group, shell, locked) {}

@class_parameter = {"login"= 0}
@supported_formats  = ["cf", "dsc"]
user state fullname(fullname) {}

@class_parameter = {"user"= 0}
@supported_formats  = ["cf", "dsc"]
user state group(group_name) {}

@class_parameter = {"login"= 0}
@supported_formats  = ["cf", "dsc"]
user state home(home) {}

@class_parameter = {"login"= 0}
@supported_formats  = ["cf", "dsc"]
user state locked() {}

@class_parameter = {"login"= 0}
@supported_formats  = ["cf", "dsc"]
user state password_hash(password) {}

@class_parameter = {"login"= 0}
@supported_formats  = ["cf", "dsc"]
user state present() {}

@class_parameter = {"login"= 0}
@supported_formats  = ["cf", "dsc"]
user state primary_group(primary_group) {}

@class_parameter = {"login"= 0}
@supported_formats  = ["cf", "dsc"]
user state shell(shell) {}

@class_parameter = {"login"= 0}
@supported_formats  = ["cf", "dsc"]
user state uid(uid) {}

@class_parameter = {"variable_name"= 1}
@supported_formats  = ["cf", "dsc"]
variable state dict(variable_prefix, value) {}

@class_parameter = {"variable_name"= 1}
@supported_formats  = ["cf", "dsc"]
variable state dict_from_file(variable_prefix, file_name) {}

@class_parameter = {"variable_name"= 1}
@supported_formats  = ["cf", "dsc"]
variable state dict_from_file_type(variable_prefix, file_name, file_type) {}

@class_parameter = {"variable_name"= 1}
@supported_formats  = ["cf", "dsc"]
variable state dict_from_osquery(variable_prefix, query) {}

@class_parameter = {"variable_name"= 1}
@supported_formats  = ["cf", "dsc"]
variable state dict_merge(variable_prefix, first_variable, second_variable) {}

@class_parameter = {"variable_name"= 1}
@supported_formats  = ["cf", "dsc"]
variable state dict_merge_tolerant(variable_prefix, first_variable, second_variable) {}

@class_parameter = {"variable_name"= 1}
@supported_formats  = ["cf", "dsc"]
variable state iterator(variable_prefix, value, separator) {}

@class_parameter = {"variable_name"= 1}
@supported_formats  = ["cf", "dsc"]
variable state iterator_from_file(variable_prefix, file_name, separator_regex, comments_regex) {}

@class_parameter = {"variable_name"= 1}
@supported_formats  = ["cf", "dsc"]
variable state string(variable_prefix, value) {}

@class_parameter = {"variable_name"= 1}
@supported_formats  = ["cf", "dsc"]
variable state string_default(variable_prefix, source_variable, default_value) {}

@class_parameter = {"variable_name"= 1}
@supported_formats  = ["cf", "dsc"]
variable state string_from_augeas(variable_prefix, path, lens, file) {}

@class_parameter = {"variable_name"= 1}
@supported_formats  = ["cf", "dsc"]
variable state string_from_command(variable_prefix, command) {}

@class_parameter = {"variable_name"= 1}
@supported_formats  = ["cf", "dsc"]
variable state string_from_file(variable_prefix, file_name) {}

@class_parameter = {"variable_name"= 1}
@supported_formats  = ["cf", "dsc"]
variable state string_from_math_expression(variable_prefix, expression, format) {}

@class_parameter = {"variable_name"= 0}
@supported_formats  = ["cf", "dsc"]
variable state string_match(expected_match) {}


