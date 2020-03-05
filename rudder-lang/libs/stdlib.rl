@format=0

resource directory(p0)
resource service(p0)
resource environment(p0)
resource kernel_module(p0)
resource user(p0)
resource file(p0)
resource command(p0)
resource permissions(p0)
resource http_request(p0,p1)
resource schedule(p0)
resource condition(p0)
resource package(p0)
resource sharedfile(p0,p1)
resource variable(p0,p1)
resource monitoring(p0)
resource group(p0)

command state execution(){}
command state execution_once(p1,p2,p3){}
command state execution_result(p1,p2){}
condition state from_command(p1,p2,p3){}
condition state from_expression(p1){}
condition state from_expression_persistent(p1,p2){}
condition state from_variable_existence(p1){}
condition state from_variable_match(p1,p2){}
condition state once(){}
directory state absent(p1){}
directory state check_exists(){}
directory state create(){}
directory state present(){}
environment state variable_present(p1){}
file state absent(){}
file state block_present(p1){}
file state block_present_in_section(p1,p2,p3){}
file state check_block_device(){}
file state check_character_device(){}
file state check_exists(){}
file state check_FIFO_pipe(){}
file state check_hardlink(p1){}
file state check_regular(){}
file state check_socket(){}
file state check_symlink(){}
file state check_symlinkto(p1){}
file state content(p1,p2){}
file state copy_from_local_source(p1){}
file state copy_from_local_source_recursion(p1,p2){}
file state copy_from_local_source_with_check(p1,p2,p3){}
file state copy_from_remote_source(p1){}
file state copy_from_remote_source_recursion(p1,p2){}
file state create(){}
file state create_symlink(p1){}
file state create_symlink_enforce(p1,p2){}
file state create_symlink_force(p1){}
file state download(p1){}
file state enforce_content(p1,p2){}
file state ensure_block_in_section(p1,p2,p3){}
file state ensure_block_present(p1){}
file state ensure_key_value(p1,p2,p3){}
file state ensure_key_value_option(p1,p2,p3,p4){}
file state ensure_key_value_parameter_in_list(p1,p2,p3,p4,p5,p6){}
file state ensure_key_value_parameter_not_in_list(p1,p2,p3,p4,p5,p6){}
file state ensure_key_value_present_in_ini_section(p1,p2,p3){}
file state ensure_keys_values(p1,p2){}
file state ensure_line_present_in_ini_section(p1,p2){}
file state ensure_line_present_in_xml_tag(p1,p2){}
file state ensure_lines_absent(p1){}
file state ensure_lines_present(p1){}
file state from_http_server(p1){}
file state from_local_source(p1){}
file state from_local_source_recursion(p1,p2){}
file state from_local_source_with_check(p1,p2,p3){}
file state from_remote_source(p1){}
file state from_remote_source_recursion(p1,p2){}
file state from_shared_folder(p1,p2){}
file state from_string_mustache(p1){}
file state from_template(p1){}
file state from_template_jinja2(p1){}
file state from_template_mustache(p1){}
file state from_template_type(p1,p2){}
file state key_value_parameter_absent_in_list(p1,p2,p3,p4,p5,p6){}
file state key_value_parameter_present_in_list(p1,p2,p3,p4,p5,p6){}
file state key_value_present(p1,p2,p3){}
file state key_value_present_in_ini_section(p1,p2,p3){}
file state key_value_present_option(p1,p2,p3,p4){}
file state keys_values_present(p1,p2){}
file state line_present_in_ini_section(p1,p2){}
file state line_present_in_xml_tag(p1,p2){}
file state lines_absent(p1){}
file state lines_present(p1){}
file state present(){}
file state remove(){}
file state replace_lines(p1,p2){}
file state report_content(p1,p2){}
file state report_content_head(p1){}
file state report_content_tail(p1){}
file state symlink_present(p1){}
file state symlink_present_force(p1){}
file state symlink_present_option(p1,p2){}
file state template_expand(p1,p2,p3,p4){}
group state absent(){}
group state present(){}
http_request state check_status_headers(p2,p3){}
http_request state content_headers(p2,p3){}
kernel_module state configuration(p1){}
kernel_module state enabled_at_boot(){}
kernel_module state loaded(){}
kernel_module state not_loaded(){}
monitoring state parameter(p1){}
monitoring state template(){}
package state absent(p1,p2,p3){}
package state check_installed(){}
package state install(){}
package state install_version(p1){}
package state install_version_cmp(p1,p2,p3){}
package state install_version_cmp_update(p1,p2,p3,p4){}
package state present(p1,p2,p3){}
package state remove(){}
package state state(p1,p2,p3,p4){}
package state state_options(p1,p2,p3,p4,p5){}
package state verify(){}
package state verify_version(p1){}
permissions state acl_entry(p1,p2,p3,p4){}
permissions state dirs(p1,p2,p3){}
permissions state dirs_recurse(p1,p2,p3){}
permissions state dirs_recursive(p1,p2,p3){}
permissions state group_acl_absent(p1,p2){}
permissions state group_acl_present(p1,p2,p3){}
permissions state other_acl_present(p1,p2){}
permissions state posix_acls_absent(p1){}
permissions state recurse(p1,p2,p3){}
permissions state recursive(p1,p2,p3){}
permissions state type_recursion(p1,p2,p3,p4,p5){}
permissions state user_acl_absent(p1,p2){}
permissions state user_acl_present(p1,p2,p3){}
schedule state simple(p1,p2,p3,p4,p5,p6,p7,p8,p9,p10){}
schedule state simple_catchup(p1,p2,p3,p4,p5,p6,p7,p8,p9){}
schedule state simple_nodups(p1,p2,p3,p4,p5,p6,p7,p8,p9){}
schedule state simple_stateless(p1,p2,p3,p4,p5,p6,p7,p8,p9){}
service state action(p1){}
service state check_disabled_at_boot(){}
service state check_running(){}
service state check_running_ps(){}
service state check_started_at_boot(){}
service state disabled(){}
service state enabled(){}
service state ensure_disabled_at_boot(){}
service state ensure_running(){}
service state ensure_running_path(p1){}
service state ensure_started_at_boot(){}
service state ensure_stopped(){}
service state reload(){}
service state restart(){}
service state restart_if(p1){}
service state start(){}
service state started(){}
service state started_path(p1){}
service state stop(){}
service state stopped(){}
sharedfile state from_node(p2){}
sharedfile state to_node(p2,p3){}
user state absent(){}
user state create(p1,p2,p3,p4,p5){}
user state fullname(p1){}
user state home(p1){}
user state locked(){}
user state password_hash(p1){}
user state present(){}
user state primary_group(p1){}
user state shell(p1){}
user state uid(p1){}
variable state dict(p2){}
variable state dict_from_file(p2){}
variable state dict_from_file_type(p2,p3){}
variable state dict_from_osquery(p2){}
variable state dict_merge(p2,p3){}
variable state dict_merge_tolerant(p2,p3){}
variable state iterator(p2,p3){}
variable state iterator_from_file(p2,p3,p4){}
variable state string(p2){}
variable state string_default(p2,p3){}
variable state string_from_command(p2){}
variable state string_from_file(p2){}
variable state string_from_math_expression(p2,p3){}
variable state string_match(){}

