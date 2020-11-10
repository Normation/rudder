# Generated from json technique
@format = 0
@name = "supported_formats"
@description = "a gm supported only by cfengine, another one supported only by dsc, and one that is supported by both"
@version = "1.0"
@category = "ncf_techniques"
@parameters = []

resource supported_formats()

supported_formats state technique() {
  @component = "Condition once"
  condition("cfengine_only").once() as condition_once_cfengine_only

  @component = "Directory present"
  directory("shared_cf_dsc").present() as directory_present_shared_cf_dsc

  @component = "Directory present"
  if windows => directory("shared_cf_dsc_condition").present() as directory_present_shared_cf_dsc_condition

  @component = "Registry key present"
  registry("DSC_ONLY").key_present() as registry_key_present_DSC_ONLY

  @component = "Registry key present"
  if windows => registry("IF_DSC_ONLY").key_present() as registry_key_present_IF_DSC_ONLY
}
