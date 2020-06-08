@format=0

resource directory(p0)
resource variable(p0, p1)
resource user(p0)
resource command(p0)
resource http_request(p0, p1)
resource service(p0)
resource kernel_module(p0)
resource group(p0)
resource condition(p0)
resource file(p0)
resource schedule(p0)
resource sysctl(p0)
resource environment(p0)
resource monitoring(p0)
resource sharedfile(p0, p1)
resource permissions(p0)
resource package(p0)

@class_parameters={"command": 0}
command state execution() {}

@class_parameters={"command": 0}
command state execution_once(ok_codes, until, unique_id) {}

@class_parameters={"command": 0}
command state execution_result(kept_codes, repaired_codes) {}

@class_parameters={"condition_prefix": 0}
condition state from_command(command, true_codes, false_codes) {}

@class_parameters={"condition_prefix": 0}
condition state from_expression(condition_expression) {}

@class_parameters={"condition_prefix": 0}
condition state from_expression_persistent(condition_expression, duration) {}

@class_parameters={"condition_prefix": 0}
condition state from_variable_existence(variable_name) {}

@class_parameters={"condition_prefix": 0}
condition state from_variable_match(variable_name, expected_match) {}

@class_parameters={"condition": 0}
condition state once() {}

@class_parameters={"target": 0}
directory state absent(recursive) {}

@class_parameters={"directory_name": 0}
directory state check_exists() {}

@class_parameters={"target": 0}
directory state create() {}

@class_parameters={"target": 0}
directory state present() {}

@class_parameters={"name": 0}
environment state variable_present(value) {}

@class_parameters={"target": 0}
file state absent() {}

@class_parameters={"variable_name": 1}
file state augeas_commands(variable_prefix, commands, autoload) {}

@class_parameters={"lens": 2}
file state augeas_set(path, value, file) {}

@class_parameters={"file": 0}
file state block_present(block) {}

@class_parameters={"file": 0}
file state block_present_in_section(section_start, section_end, block) {}

@class_parameters={"file_name": 0}
file state check_block_device() {}

@class_parameters={"file_name": 0}
file state check_character_device() {}

@class_parameters={"file_name": 0}
file state check_exists() {}

@class_parameters={"file_name": 0}
file state check_FIFO_pipe() {}

@class_parameters={"file_name_1": 0}
file state check_hardlink(file_name_2) {}

@class_parameters={"file_name": 0}
file state check_regular() {}

@class_parameters={"file_name": 0}
file state check_socket() {}

@class_parameters={"file_name": 0}
file state check_symlink() {}

@class_parameters={"symlink": 0}
file state check_symlinkto(target) {}

@class_parameters={"file": 0}
file state content(lines, enforce) {}

@class_parameters={"destination": 1}
file state copy_from_local_source(source) {}

@class_parameters={"destination": 1}
file state copy_from_local_source_recursion(source, recursion) {}

@class_parameters={"destination": 1}
file state copy_from_local_source_with_check(source, check_command, rc_ok) {}

@class_parameters={"destination": 1}
file state copy_from_remote_source(source) {}

@class_parameters={"destination": 1}
file state copy_from_remote_source_recursion(source, recursion) {}

@class_parameters={"target": 0}
file state create() {}

@class_parameters={"destination": 1}
file state create_symlink(source) {}

@class_parameters={"destination": 1}
file state create_symlink_enforce(source, enforce) {}

@class_parameters={"destination": 1}
file state create_symlink_force(source) {}

@class_parameters={"destination": 1}
file state download(source) {}

@class_parameters={"file": 0}
file state enforce_content(lines, enforce) {}

@class_parameters={"file": 0}
file state ensure_block_in_section(section_start, section_end, block) {}

@class_parameters={"file": 0}
file state ensure_block_present(block) {}

@class_parameters={"file": 0}
file state ensure_key_value(key, value, separator) {}

@class_parameters={"file": 0}
file state ensure_key_value_option(key, value, option, separator) {}

@class_parameters={"file": 0}
file state ensure_key_value_parameter_in_list(key, key_value_separator, parameter, parameter_separator, leading_char_separator, closing_char_separator) {}

@class_parameters={"file": 0}
file state ensure_key_value_parameter_not_in_list(key, key_value_separator, parameter_regex, parameter_separator, leading_char_separator, closing_char_separator) {}

@class_parameters={"file": 0}
file state ensure_key_value_present_in_ini_section(section, name, value) {}

@class_parameters={"file": 0}
file state ensure_keys_values(keys, separator) {}

@class_parameters={"file": 0}
file state ensure_line_present_in_ini_section(section, line) {}

@class_parameters={"file": 0}
file state ensure_line_present_in_xml_tag(tag, line) {}

@class_parameters={"file": 0}
file state ensure_lines_absent(lines) {}

@class_parameters={"file": 0}
file state ensure_lines_present(lines) {}

@class_parameters={"destination": 1}
file state from_http_server(source) {}

@class_parameters={"destination": 1}
file state from_local_source(source) {}

@class_parameters={"destination": 1}
file state from_local_source_recursion(source, recursion) {}

@class_parameters={"destination": 1}
file state from_local_source_with_check(source, check_command, rc_ok) {}

@class_parameters={"destination": 1}
file state from_remote_source(source) {}

@class_parameters={"destination": 1}
file state from_remote_source_recursion(source, recursion) {}

@class_parameters={"destination": 1}
file state from_shared_folder(source, hash_type) {}

@class_parameters={"destination": 1}
file state from_string_mustache(template) {}

@class_parameters={"destination": 1}
file state from_template(source_template) {}

@class_parameters={"destination": 1}
file state from_template_jinja2(source_template) {}

@class_parameters={"destination": 1}
file state from_template_mustache(source_template) {}

@class_parameters={"destination": 1}
file state from_template_type(source_template, template_type) {}

@class_parameters={"file": 0}
file state key_value_parameter_absent_in_list(key, key_value_separator, parameter_regex, parameter_separator, leading_char_separator, closing_char_separator) {}

@class_parameters={"file": 0}
file state key_value_parameter_present_in_list(key, key_value_separator, parameter, parameter_separator, leading_char_separator, closing_char_separator) {}

@class_parameters={"file": 0}
file state key_value_present(key, value, separator) {}

@class_parameters={"file": 0}
file state key_value_present_in_ini_section(section, name, value) {}

@class_parameters={"file": 0}
file state key_value_present_option(key, value, separator, option) {}

@class_parameters={"file": 0}
file state keys_values_present(keys, separator) {}

@class_parameters={"file": 0}
file state line_present_in_ini_section(section, line) {}

@class_parameters={"file": 0}
file state line_present_in_xml_tag(tag, line) {}

@class_parameters={"file": 0}
file state lines_absent(lines) {}

@class_parameters={"file": 0}
file state lines_present(lines) {}

@class_parameters={"target": 0}
file state present() {}

@class_parameters={"target": 0}
file state remove() {}

@class_parameters={"file": 0}
file state replace_lines(line, replacement) {}

@class_parameters={"target": 0}
file state report_content(regex, context) {}

@class_parameters={"target": 0}
file state report_content_head(limit) {}

@class_parameters={"target": 0}
file state report_content_tail(limit) {}

@class_parameters={"destination": 1}
file state symlink_present(source) {}

@class_parameters={"destination": 1}
file state symlink_present_force(source) {}

@class_parameters={"destination": 1}
file state symlink_present_option(source, enforce) {}

@class_parameters={"target_file": 1}
file state template_expand(tml_file, mode, owner, group) {}

@class_parameters={"group": 0}
group state absent() {}

@class_parameters={"group": 0}
group state present() {}

@class_parameters={"url": 1}
http_request state check_status_headers(method, expected_status, headers) {}

@class_parameters={"url": 1}
http_request state content_headers(method, content, headers) {}

@class_parameters={"module_name": 0}
kernel_module state configuration(configuration) {}

@class_parameters={"module_name": 0}
kernel_module state enabled_at_boot() {}

@class_parameters={"module_name": 0}
kernel_module state loaded() {}

@class_parameters={"module_name": 0}
kernel_module state not_loaded() {}

@class_parameters={"key": 0}
monitoring state parameter(value) {}

@class_parameters={"template": 0}
monitoring state template() {}

@class_parameters={"name": 0}
package state absent(version, architecture, provider) {}

@class_parameters={"package_name": 0}
package state check_installed() {}

@class_parameters={"package_name": 0}
package state install() {}

@class_parameters={"package_name": 0}
package state install_version(package_version) {}

@class_parameters={"package_name": 0}
package state install_version_cmp(version_comparator, package_version, action) {}

@class_parameters={"package_name": 0}
package state install_version_cmp_update(version_comparator, package_version, action, update_policy) {}

@class_parameters={"name": 0}
package state present(version, architecture, provider) {}

@class_parameters={"package_name": 0}
package state remove() {}

@class_parameters={"name": 0}
package state state(version, architecture, provider, state) {}

@class_parameters={"name": 0}
package state state_options(version, architecture, provider, state, options) {}

@class_parameters={"package_name": 0}
package state verify() {}

@class_parameters={"package_name": 0}
package state verify_version(package_version) {}

@class_parameters={"path": 0}
permissions state acl_entry(recursive, user, group, other) {}

@class_parameters={"path": 0}
permissions state dirs(mode, owner, group) {}

@class_parameters={"path": 0}
permissions state dirs_recurse(mode, owner, group) {}

@class_parameters={"path": 0}
permissions state dirs_recursive(mode, owner, group) {}

@class_parameters={"path": 0}
permissions state group_acl_absent(recursive, group) {}

@class_parameters={"path": 0}
permissions state group_acl_present(recursive, group, ace) {}

@class_parameters={"path": 0}
permissions state other_acl_present(recursive, other) {}

@class_parameters={"path": 0}
permissions state posix_acls_absent(recursive) {}

@class_parameters={"path": 0}
permissions state recurse(mode, owner, group) {}

@class_parameters={"path": 0}
permissions state recursive(mode, owner, group) {}

@class_parameters={"path": 0}
permissions state type_recursion(mode, owner, group, type, recursion) {}

@class_parameters={"path": 0}
permissions state user_acl_absent(recursive, user) {}

@class_parameters={"path": 0}
permissions state user_acl_present(recursive, user, ace) {}

@class_parameters={"job_id": 0}
schedule state simple(agent_periodicity, max_execution_delay_minutes, max_execution_delay_hours, start_on_minutes, start_on_hours, start_on_day_of_week, periodicity_minutes, periodicity_hours, periodicity_days, mode) {}

@class_parameters={"job_id": 0}
schedule state simple_catchup(agent_periodicity, max_execution_delay_minutes, max_execution_delay_hours, start_on_minutes, start_on_hours, start_on_day_of_week, periodicity_minutes, periodicity_hours, periodicity_days) {}

@class_parameters={"job_id": 0}
schedule state simple_nodups(agent_periodicity, max_execution_delay_minutes, max_execution_delay_hours, start_on_minutes, start_on_hours, start_on_day_of_week, periodicity_minutes, periodicity_hours, periodicity_days) {}

@class_parameters={"job_id": 0}
schedule state simple_stateless(agent_periodicity, max_execution_delay_minutes, max_execution_delay_hours, start_on_minutes, start_on_hours, start_on_day_of_week, periodicity_minutes, periodicity_hours, periodicity_days) {}

@class_parameters={"service_name": 0}
service state action(action) {}

@class_parameters={"service_name": 0}
service state check_disabled_at_boot() {}

@class_parameters={"service_name": 0}
service state check_running() {}

@class_parameters={"service_regex": 0}
service state check_running_ps() {}

@class_parameters={"service_name": 0}
service state check_started_at_boot() {}

@class_parameters={"service_name": 0}
service state disabled() {}

@class_parameters={"service_name": 0}
service state enabled() {}

@class_parameters={"service_name": 0}
service state ensure_disabled_at_boot() {}

@class_parameters={"service_name": 0}
service state ensure_running() {}

@class_parameters={"service_name": 0}
service state ensure_running_path(service_path) {}

@class_parameters={"service_name": 0}
service state ensure_started_at_boot() {}

@class_parameters={"service_name": 0}
service state ensure_stopped() {}

@class_parameters={"service_name": 0}
service state reload() {}

@class_parameters={"service_name": 0}
service state restart() {}

@class_parameters={"service_name": 0}
service state restart_if(trigger_class) {}

@class_parameters={"service_name": 0}
service state start() {}

@class_parameters={"service_name": 0}
service state started() {}

@class_parameters={"service_name": 0}
service state started_path(service_path) {}

@class_parameters={"service_name": 0}
service state stop() {}

@class_parameters={"service_name": 0}
service state stopped() {}

@class_parameters={"file_id": 1}
sharedfile state from_node(source_uuid, file_path) {}

@class_parameters={"file_id": 1}
sharedfile state to_node(target_uuid, file_path, ttl) {}

@class_parameters={"key": 0}
sysctl state value(value, filename, option) {}

@class_parameters={"login": 0}
user state absent() {}

@class_parameters={"login": 0}
user state create(description, home, group, shell, locked) {}

@class_parameters={"login": 0}
user state fullname(fullname) {}

@class_parameters={"user": 0}
user state group(group_name) {}

@class_parameters={"login": 0}
user state home(home) {}

@class_parameters={"login": 0}
user state locked() {}

@class_parameters={"login": 0}
user state password_hash(password) {}

@class_parameters={"login": 0}
user state present() {}

@class_parameters={"login": 0}
user state primary_group(primary_group) {}

@class_parameters={"login": 0}
user state shell(shell) {}

@class_parameters={"login": 0}
user state uid(uid) {}

@class_parameters={"variable_name": 1}
variable state dict(variable_prefix, value) {}

@class_parameters={"variable_name": 1}
variable state dict_from_file(variable_prefix, file_name) {}

@class_parameters={"variable_name": 1}
variable state dict_from_file_type(variable_prefix, file_name, file_type) {}

@class_parameters={"variable_name": 1}
variable state dict_from_osquery(variable_prefix, query) {}

@class_parameters={"variable_name": 1}
variable state dict_merge(variable_prefix, first_variable, second_variable) {}

@class_parameters={"variable_name": 1}
variable state dict_merge_tolerant(variable_prefix, first_variable, second_variable) {}

@class_parameters={"variable_name": 1}
variable state iterator(variable_prefix, value, separator) {}

@class_parameters={"variable_name": 1}
variable state iterator_from_file(variable_prefix, file_name, separator_regex, comments_regex) {}

@class_parameters={"variable_name": 1}
variable state string(variable_prefix, value) {}

@class_parameters={"variable_name": 1}
variable state string_default(variable_prefix, source_variable, default_value) {}

@class_parameters={"variable_name": 1}
variable state string_from_augeas(variable_prefix, path, lens, file) {}

@class_parameters={"variable_name": 1}
variable state string_from_command(variable_prefix, command) {}

@class_parameters={"variable_name": 1}
variable state string_from_file(variable_prefix, file_name) {}

@class_parameters={"variable_name": 1}
variable state string_from_math_expression(variable_prefix, expression, format) {}

@class_parameters={"variable_name": 0}
variable state string_match(expected_match) {}


