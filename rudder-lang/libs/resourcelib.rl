@format=0

resource package(p0)
resource permissions(p0)
resource variable(p0, p1)
resource service(p0)
resource schedule(p0)
resource sharedfile(p0, p1)
resource user(p0)
resource environment(p0)
resource command(p0)
resource file(p0)
resource sysctl(p0)
resource condition(p0)
resource http_request(p0, p1)
resource group(p0)
resource kernel_module(p0)
resource directory(p0)
resource monitoring(p0)

@resource_parameter = {"command": 0}
@supported_formats  = ["cf", "dsc"]
command state execution() {}

@resource_parameter = {"command": 0}
@supported_formats  = ["cf", "dsc"]
command state execution_once(ok_codes, until, unique_id) {}

@resource_parameter = {"command": 0}
@supported_formats  = ["cf", "dsc"]
command state execution_result(kept_codes, repaired_codes) {}

@resource_parameter = {"condition_prefix": 0}
@supported_formats  = ["cf", "dsc"]
condition state from_command(command, true_codes, false_codes) {}

@resource_parameter = {"condition_prefix": 0}
@supported_formats  = ["cf", "dsc"]
condition state from_expression(condition_expression) {}

@resource_parameter = {"condition_prefix": 0}
@supported_formats  = ["cf", "dsc"]
condition state from_expression_persistent(condition_expression, duration) {}

@resource_parameter = {"condition_prefix": 0}
@supported_formats  = ["cf", "dsc"]
condition state from_variable_existence(variable_name) {}

@resource_parameter = {"condition_prefix": 0}
@supported_formats  = ["cf", "dsc"]
condition state from_variable_match(variable_name, expected_match) {}

@resource_parameter = {"condition": 0}
@supported_formats  = ["cf", "dsc"]
condition state once() {}

@resource_parameter = {"target": 0}
@supported_formats  = ["cf", "dsc"]
directory state absent(recursive) {}

@resource_parameter = {"directory_name": 0}
@supported_formats  = ["cf", "dsc"]
directory state check_exists() {}

@resource_parameter = {"target": 0}
@supported_formats  = ["cf", "dsc"]
directory state create() {}

@resource_parameter = {"target": 0}
@supported_formats  = ["cf", "dsc"]
directory state present() {}

@resource_parameter = {"name": 0}
@supported_formats  = ["cf", "dsc"]
environment state variable_present(value) {}

@resource_parameter = {"target": 0}
@supported_formats  = ["cf", "dsc"]
file state absent() {}

@resource_parameter = {"variable_name": 1}
@supported_formats  = ["cf", "dsc"]
file state augeas_commands(variable_prefix, commands, autoload) {}

@resource_parameter = {"lens": 2}
@supported_formats  = ["cf", "dsc"]
file state augeas_set(path, value, file) {}

@resource_parameter = {"file": 0}
@supported_formats  = ["cf", "dsc"]
file state block_present(block) {}

@resource_parameter = {"file": 0}
@supported_formats  = ["cf", "dsc"]
file state block_present_in_section(section_start, section_end, block) {}

@resource_parameter = {"file_name": 0}
@supported_formats  = ["cf", "dsc"]
file state check_block_device() {}

@resource_parameter = {"file_name": 0}
@supported_formats  = ["cf", "dsc"]
file state check_character_device() {}

@resource_parameter = {"file_name": 0}
@supported_formats  = ["cf", "dsc"]
file state check_exists() {}

@resource_parameter = {"file_name": 0}
@supported_formats  = ["cf", "dsc"]
file state check_FIFO_pipe() {}

@resource_parameter = {"file_name_1": 0}
@supported_formats  = ["cf", "dsc"]
file state check_hardlink(file_name_2) {}

@resource_parameter = {"file_name": 0}
@supported_formats  = ["cf", "dsc"]
file state check_regular() {}

@resource_parameter = {"file_name": 0}
@supported_formats  = ["cf", "dsc"]
file state check_socket() {}

@resource_parameter = {"file_name": 0}
@supported_formats  = ["cf", "dsc"]
file state check_symlink() {}

@resource_parameter = {"symlink": 0}
@supported_formats  = ["cf", "dsc"]
file state check_symlinkto(target) {}

@resource_parameter = {"file": 0}
@supported_formats  = ["cf", "dsc"]
file state content(lines, enforce) {}

@resource_parameter = {"destination": 1}
@supported_formats  = ["cf", "dsc"]
file state copy_from_local_source(source) {}

@resource_parameter = {"destination": 1}
@supported_formats  = ["cf", "dsc"]
file state copy_from_local_source_recursion(source, recursion) {}

@resource_parameter = {"destination": 1}
@supported_formats  = ["cf", "dsc"]
file state copy_from_local_source_with_check(source, check_command, rc_ok) {}

@resource_parameter = {"destination": 1}
@supported_formats  = ["cf", "dsc"]
file state copy_from_remote_source(source) {}

@resource_parameter = {"destination": 1}
@supported_formats  = ["cf", "dsc"]
file state copy_from_remote_source_recursion(source, recursion) {}

@resource_parameter = {"target": 0}
@supported_formats  = ["cf", "dsc"]
file state create() {}

@resource_parameter = {"destination": 1}
@supported_formats  = ["cf", "dsc"]
file state create_symlink(source) {}

@resource_parameter = {"destination": 1}
@supported_formats  = ["cf", "dsc"]
file state create_symlink_enforce(source, enforce) {}

@resource_parameter = {"destination": 1}
@supported_formats  = ["cf", "dsc"]
file state create_symlink_force(source) {}

@resource_parameter = {"destination": 1}
@supported_formats  = ["cf", "dsc"]
file state download(source) {}

@resource_parameter = {"file": 0}
@supported_formats  = ["cf", "dsc"]
file state enforce_content(lines, enforce) {}

@resource_parameter = {"file": 0}
@supported_formats  = ["cf", "dsc"]
file state ensure_block_in_section(section_start, section_end, block) {}

@resource_parameter = {"file": 0}
@supported_formats  = ["cf", "dsc"]
file state ensure_block_present(block) {}

@resource_parameter = {"file": 0}
@supported_formats  = ["cf", "dsc"]
file state ensure_key_value(key, value, separator) {}

@resource_parameter = {"file": 0}
@supported_formats  = ["cf", "dsc"]
file state ensure_key_value_option(key, value, option, separator) {}

@resource_parameter = {"file": 0}
@supported_formats  = ["cf", "dsc"]
file state ensure_key_value_parameter_in_list(key, key_value_separator, parameter, parameter_separator, leading_char_separator, closing_char_separator) {}

@resource_parameter = {"file": 0}
@supported_formats  = ["cf", "dsc"]
file state ensure_key_value_parameter_not_in_list(key, key_value_separator, parameter_regex, parameter_separator, leading_char_separator, closing_char_separator) {}

@resource_parameter = {"file": 0}
@supported_formats  = ["cf", "dsc"]
file state ensure_key_value_present_in_ini_section(section, name, value) {}

@resource_parameter = {"file": 0}
@supported_formats  = ["cf", "dsc"]
file state ensure_keys_values(keys, separator) {}

@resource_parameter = {"file": 0}
@supported_formats  = ["cf", "dsc"]
file state ensure_line_present_in_ini_section(section, line) {}

@resource_parameter = {"file": 0}
@supported_formats  = ["cf", "dsc"]
file state ensure_line_present_in_xml_tag(tag, line) {}

@resource_parameter = {"file": 0}
@supported_formats  = ["cf", "dsc"]
file state ensure_lines_absent(lines) {}

@resource_parameter = {"file": 0}
@supported_formats  = ["cf", "dsc"]
file state ensure_lines_present(lines) {}

@resource_parameter = {"destination": 1}
@supported_formats  = ["cf", "dsc"]
file state from_http_server(source) {}

@resource_parameter = {"destination": 1}
@supported_formats  = ["cf", "dsc"]
file state from_local_source(source) {}

@resource_parameter = {"destination": 1}
@supported_formats  = ["cf", "dsc"]
file state from_local_source_recursion(source, recursion) {}

@resource_parameter = {"destination": 1}
@supported_formats  = ["cf", "dsc"]
file state from_local_source_with_check(source, check_command, rc_ok) {}

@resource_parameter = {"destination": 1}
@supported_formats  = ["cf", "dsc"]
file state from_remote_source(source) {}

@resource_parameter = {"destination": 1}
@supported_formats  = ["cf", "dsc"]
file state from_remote_source_recursion(source, recursion) {}

@resource_parameter = {"destination": 1}
@supported_formats  = ["cf", "dsc"]
file state from_shared_folder(source, hash_type) {}

@resource_parameter = {"destination": 1}
@supported_formats  = ["cf", "dsc"]
file state from_string_mustache(template) {}

@resource_parameter = {"destination": 1}
@supported_formats  = ["cf", "dsc"]
file state from_template(source_template) {}

@resource_parameter = {"destination": 1}
@supported_formats  = ["cf", "dsc"]
file state from_template_jinja2(source_template) {}

@resource_parameter = {"destination": 1}
@supported_formats  = ["cf", "dsc"]
file state from_template_mustache(source_template) {}

@resource_parameter = {"destination": 1}
@supported_formats  = ["cf", "dsc"]
file state from_template_type(source_template, template_type) {}

@resource_parameter = {"file": 0}
@supported_formats  = ["cf", "dsc"]
file state key_value_parameter_absent_in_list(key, key_value_separator, parameter_regex, parameter_separator, leading_char_separator, closing_char_separator) {}

@resource_parameter = {"file": 0}
@supported_formats  = ["cf", "dsc"]
file state key_value_parameter_present_in_list(key, key_value_separator, parameter, parameter_separator, leading_char_separator, closing_char_separator) {}

@resource_parameter = {"file": 0}
@supported_formats  = ["cf", "dsc"]
file state key_value_present(key, value, separator) {}

@resource_parameter = {"file": 0}
@supported_formats  = ["cf", "dsc"]
file state key_value_present_in_ini_section(section, name, value) {}

@resource_parameter = {"file": 0}
@supported_formats  = ["cf", "dsc"]
file state key_value_present_option(key, value, separator, option) {}

@resource_parameter = {"file": 0}
@supported_formats  = ["cf", "dsc"]
file state keys_values_present(keys, separator) {}

@resource_parameter = {"file": 0}
@supported_formats  = ["cf", "dsc"]
file state line_present_in_ini_section(section, line) {}

@resource_parameter = {"file": 0}
@supported_formats  = ["cf", "dsc"]
file state line_present_in_xml_tag(tag, line) {}

@resource_parameter = {"file": 0}
@supported_formats  = ["cf", "dsc"]
file state lines_absent(lines) {}

@resource_parameter = {"file": 0}
@supported_formats  = ["cf", "dsc"]
file state lines_present(lines) {}

@resource_parameter = {"target": 0}
@supported_formats  = ["cf", "dsc"]
file state present() {}

@resource_parameter = {"target": 0}
@supported_formats  = ["cf", "dsc"]
file state remove() {}

@resource_parameter = {"file": 0}
@supported_formats  = ["cf", "dsc"]
file state replace_lines(line, replacement) {}

@resource_parameter = {"target": 0}
@supported_formats  = ["cf", "dsc"]
file state report_content(regex, context) {}

@resource_parameter = {"target": 0}
@supported_formats  = ["cf", "dsc"]
file state report_content_head(limit) {}

@resource_parameter = {"target": 0}
@supported_formats  = ["cf", "dsc"]
file state report_content_tail(limit) {}

@resource_parameter = {"destination": 1}
@supported_formats  = ["cf", "dsc"]
file state symlink_present(source) {}

@resource_parameter = {"destination": 1}
@supported_formats  = ["cf", "dsc"]
file state symlink_present_force(source) {}

@resource_parameter = {"destination": 1}
@supported_formats  = ["cf", "dsc"]
file state symlink_present_option(source, enforce) {}

@resource_parameter = {"target_file": 1}
@supported_formats  = ["cf", "dsc"]
file state template_expand(tml_file, mode, owner, group) {}

@resource_parameter = {"group": 0}
@supported_formats  = ["cf", "dsc"]
group state absent() {}

@resource_parameter = {"group": 0}
@supported_formats  = ["cf", "dsc"]
group state present() {}

@resource_parameter = {"url": 1}
@supported_formats  = ["cf", "dsc"]
http_request state check_status_headers(method, expected_status, headers) {}

@resource_parameter = {"url": 1}
@supported_formats  = ["cf", "dsc"]
http_request state content_headers(method, content, headers) {}

@resource_parameter = {"module_name": 0}
@supported_formats  = ["cf", "dsc"]
kernel_module state configuration(configuration) {}

@resource_parameter = {"module_name": 0}
@supported_formats  = ["cf", "dsc"]
kernel_module state enabled_at_boot() {}

@resource_parameter = {"module_name": 0}
@supported_formats  = ["cf", "dsc"]
kernel_module state loaded() {}

@resource_parameter = {"module_name": 0}
@supported_formats  = ["cf", "dsc"]
kernel_module state not_loaded() {}

@resource_parameter = {"key": 0}
@supported_formats  = ["cf", "dsc"]
monitoring state parameter(value) {}

@resource_parameter = {"template": 0}
@supported_formats  = ["cf", "dsc"]
monitoring state template() {}

@resource_parameter = {"name": 0}
@supported_formats  = ["cf", "dsc"]
package state absent(version, architecture, provider) {}

@resource_parameter = {"package_name": 0}
@supported_formats  = ["cf", "dsc"]
package state check_installed() {}

@resource_parameter = {"package_name": 0}
@supported_formats  = ["cf", "dsc"]
package state install() {}

@resource_parameter = {"package_name": 0}
@supported_formats  = ["cf", "dsc"]
package state install_version(package_version) {}

@resource_parameter = {"package_name": 0}
@supported_formats  = ["cf", "dsc"]
package state install_version_cmp(version_comparator, package_version, action) {}

@resource_parameter = {"package_name": 0}
@supported_formats  = ["cf", "dsc"]
package state install_version_cmp_update(version_comparator, package_version, action, update_policy) {}

@resource_parameter = {"name": 0}
@supported_formats  = ["cf", "dsc"]
package state present(version, architecture, provider) {}

@resource_parameter = {"package_name": 0}
@supported_formats  = ["cf", "dsc"]
package state remove() {}

@resource_parameter = {"name": 0}
@supported_formats  = ["cf", "dsc"]
package state state(version, architecture, provider, state) {}

@resource_parameter = {"name": 0}
@supported_formats  = ["cf", "dsc"]
package state state_options(version, architecture, provider, state, options) {}

@resource_parameter = {"package_name": 0}
@supported_formats  = ["cf", "dsc"]
package state verify() {}

@resource_parameter = {"package_name": 0}
@supported_formats  = ["cf", "dsc"]
package state verify_version(package_version) {}

@resource_parameter = {"path": 0}
@supported_formats  = ["cf", "dsc"]
permissions state acl_entry(recursive, user, group, other) {}

@resource_parameter = {"path": 0}
@supported_formats  = ["cf", "dsc"]
permissions state dirs(mode, owner, group) {}

@resource_parameter = {"path": 0}
@supported_formats  = ["cf", "dsc"]
permissions state dirs_recurse(mode, owner, group) {}

@resource_parameter = {"path": 0}
@supported_formats  = ["cf", "dsc"]
permissions state dirs_recursive(mode, owner, group) {}

@resource_parameter = {"path": 0}
@supported_formats  = ["cf", "dsc"]
permissions state group_acl_absent(recursive, group) {}

@resource_parameter = {"path": 0}
@supported_formats  = ["cf", "dsc"]
permissions state group_acl_present(recursive, group, ace) {}

@resource_parameter = {"path": 0}
@supported_formats  = ["cf", "dsc"]
permissions state other_acl_present(recursive, other) {}

@resource_parameter = {"path": 0}
@supported_formats  = ["cf", "dsc"]
permissions state posix_acls_absent(recursive) {}

@resource_parameter = {"path": 0}
@supported_formats  = ["cf", "dsc"]
permissions state recurse(mode, owner, group) {}

@resource_parameter = {"path": 0}
@supported_formats  = ["cf", "dsc"]
permissions state recursive(mode, owner, group) {}

@resource_parameter = {"path": 0}
@supported_formats  = ["cf", "dsc"]
permissions state type_recursion(mode, owner, group, type, recursion) {}

@resource_parameter = {"path": 0}
@supported_formats  = ["cf", "dsc"]
permissions state user_acl_absent(recursive, user) {}

@resource_parameter = {"path": 0}
@supported_formats  = ["cf", "dsc"]
permissions state user_acl_present(recursive, user, ace) {}

@resource_parameter = {"job_id": 0}
@supported_formats  = ["cf", "dsc"]
schedule state simple(agent_periodicity, max_execution_delay_minutes, max_execution_delay_hours, start_on_minutes, start_on_hours, start_on_day_of_week, periodicity_minutes, periodicity_hours, periodicity_days, mode) {}

@resource_parameter = {"job_id": 0}
@supported_formats  = ["cf", "dsc"]
schedule state simple_catchup(agent_periodicity, max_execution_delay_minutes, max_execution_delay_hours, start_on_minutes, start_on_hours, start_on_day_of_week, periodicity_minutes, periodicity_hours, periodicity_days) {}

@resource_parameter = {"job_id": 0}
@supported_formats  = ["cf", "dsc"]
schedule state simple_nodups(agent_periodicity, max_execution_delay_minutes, max_execution_delay_hours, start_on_minutes, start_on_hours, start_on_day_of_week, periodicity_minutes, periodicity_hours, periodicity_days) {}

@resource_parameter = {"job_id": 0}
@supported_formats  = ["cf", "dsc"]
schedule state simple_stateless(agent_periodicity, max_execution_delay_minutes, max_execution_delay_hours, start_on_minutes, start_on_hours, start_on_day_of_week, periodicity_minutes, periodicity_hours, periodicity_days) {}

@resource_parameter = {"service_name": 0}
@supported_formats  = ["cf", "dsc"]
service state action(action) {}

@resource_parameter = {"service_name": 0}
@supported_formats  = ["cf", "dsc"]
service state check_disabled_at_boot() {}

@resource_parameter = {"service_name": 0}
@supported_formats  = ["cf", "dsc"]
service state check_running() {}

@resource_parameter = {"service_regex": 0}
@supported_formats  = ["cf", "dsc"]
service state check_running_ps() {}

@resource_parameter = {"service_name": 0}
@supported_formats  = ["cf", "dsc"]
service state check_started_at_boot() {}

@resource_parameter = {"service_name": 0}
@supported_formats  = ["cf", "dsc"]
service state disabled() {}

@resource_parameter = {"service_name": 0}
@supported_formats  = ["cf", "dsc"]
service state enabled() {}

@resource_parameter = {"service_name": 0}
@supported_formats  = ["cf", "dsc"]
service state ensure_disabled_at_boot() {}

@resource_parameter = {"service_name": 0}
@supported_formats  = ["cf", "dsc"]
service state ensure_running() {}

@resource_parameter = {"service_name": 0}
@supported_formats  = ["cf", "dsc"]
service state ensure_running_path(service_path) {}

@resource_parameter = {"service_name": 0}
@supported_formats  = ["cf", "dsc"]
service state ensure_started_at_boot() {}

@resource_parameter = {"service_name": 0}
@supported_formats  = ["cf", "dsc"]
service state ensure_stopped() {}

@resource_parameter = {"service_name": 0}
@supported_formats  = ["cf", "dsc"]
service state reload() {}

@resource_parameter = {"service_name": 0}
@supported_formats  = ["cf", "dsc"]
service state restart() {}

@resource_parameter = {"service_name": 0}
@supported_formats  = ["cf", "dsc"]
service state restart_if(trigger_class) {}

@resource_parameter = {"service_name": 0}
@supported_formats  = ["cf", "dsc"]
service state start() {}

@resource_parameter = {"service_name": 0}
@supported_formats  = ["cf", "dsc"]
service state started() {}

@resource_parameter = {"service_name": 0}
@supported_formats  = ["cf", "dsc"]
service state started_path(service_path) {}

@resource_parameter = {"service_name": 0}
@supported_formats  = ["cf", "dsc"]
service state stop() {}

@resource_parameter = {"service_name": 0}
@supported_formats  = ["cf", "dsc"]
service state stopped() {}

@resource_parameter = {"file_id": 1}
@supported_formats  = ["cf", "dsc"]
sharedfile state from_node(source_uuid, file_path) {}

@resource_parameter = {"file_id": 1}
@supported_formats  = ["cf", "dsc"]
sharedfile state to_node(target_uuid, file_path, ttl) {}

@resource_parameter = {"key": 0}
@supported_formats  = ["cf", "dsc"]
sysctl state value(value, filename, option) {}

@resource_parameter = {"login": 0}
@supported_formats  = ["cf", "dsc"]
user state absent() {}

@resource_parameter = {"login": 0}
@supported_formats  = ["cf", "dsc"]
user state create(description, home, group, shell, locked) {}

@resource_parameter = {"login": 0}
@supported_formats  = ["cf", "dsc"]
user state fullname(fullname) {}

@resource_parameter = {"user": 0}
@supported_formats  = ["cf", "dsc"]
user state group(group_name) {}

@resource_parameter = {"login": 0}
@supported_formats  = ["cf", "dsc"]
user state home(home) {}

@resource_parameter = {"login": 0}
@supported_formats  = ["cf", "dsc"]
user state locked() {}

@resource_parameter = {"login": 0}
@supported_formats  = ["cf", "dsc"]
user state password_hash(password) {}

@resource_parameter = {"login": 0}
@supported_formats  = ["cf", "dsc"]
user state present() {}

@resource_parameter = {"login": 0}
@supported_formats  = ["cf", "dsc"]
user state primary_group(primary_group) {}

@resource_parameter = {"login": 0}
@supported_formats  = ["cf", "dsc"]
user state shell(shell) {}

@resource_parameter = {"login": 0}
@supported_formats  = ["cf", "dsc"]
user state uid(uid) {}

@resource_parameter = {"variable_name": 1}
@supported_formats  = ["cf", "dsc"]
variable state dict(variable_prefix, value) {}

@resource_parameter = {"variable_name": 1}
@supported_formats  = ["cf", "dsc"]
variable state dict_from_file(variable_prefix, file_name) {}

@resource_parameter = {"variable_name": 1}
@supported_formats  = ["cf", "dsc"]
variable state dict_from_file_type(variable_prefix, file_name, file_type) {}

@resource_parameter = {"variable_name": 1}
@supported_formats  = ["cf", "dsc"]
variable state dict_from_osquery(variable_prefix, query) {}

@resource_parameter = {"variable_name": 1}
@supported_formats  = ["cf", "dsc"]
variable state dict_merge(variable_prefix, first_variable, second_variable) {}

@resource_parameter = {"variable_name": 1}
@supported_formats  = ["cf", "dsc"]
variable state dict_merge_tolerant(variable_prefix, first_variable, second_variable) {}

@resource_parameter = {"variable_name": 1}
@supported_formats  = ["cf", "dsc"]
variable state iterator(variable_prefix, value, separator) {}

@resource_parameter = {"variable_name": 1}
@supported_formats  = ["cf", "dsc"]
variable state iterator_from_file(variable_prefix, file_name, separator_regex, comments_regex) {}

@resource_parameter = {"variable_name": 1}
@supported_formats  = ["cf", "dsc"]
variable state string(variable_prefix, value) {}

@resource_parameter = {"variable_name": 1}
@supported_formats  = ["cf", "dsc"]
variable state string_default(variable_prefix, source_variable, default_value) {}

@resource_parameter = {"variable_name": 1}
@supported_formats  = ["cf", "dsc"]
variable state string_from_augeas(variable_prefix, path, lens, file) {}

@resource_parameter = {"variable_name": 1}
@supported_formats  = ["cf", "dsc"]
variable state string_from_command(variable_prefix, command) {}

@resource_parameter = {"variable_name": 1}
@supported_formats  = ["cf", "dsc"]
variable state string_from_file(variable_prefix, file_name) {}

@resource_parameter = {"variable_name": 1}
@supported_formats  = ["cf", "dsc"]
variable state string_from_math_expression(variable_prefix, expression, format) {}

@resource_parameter = {"variable_name": 0}
@supported_formats  = ["cf", "dsc"]
variable state string_match(expected_match) {}
