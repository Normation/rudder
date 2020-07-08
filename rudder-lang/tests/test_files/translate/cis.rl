# This file has been generated with rltranslate
@format=0
@name="CIS redhat7 - Enable Service"
@description="test"
@version="1.0"
@parameters= [
  { "name": "service", "id": "981a5b9d-b062-4011-8dff-df1810cb2fe6", "description": "", "constraints": "" },
]

resource CIS_redhat7___Enable_Service(service)
CIS_redhat7___Enable_Service state technique() {
  @component = "condition_from_variable_existence"
  condition("skip_item_","${report_data.canonified_directive_id}","node.properties[skip][","${report_data.directive_id}","]").from_variable_existence() as condition_from_variable_existence_skip_item___report_data_canonified_directive_id_node_properties_skip____report_data_directive_id__
  @component = "service_enabled"
  if (skip_item_${report_data.canonified_directive_id} =~ ok) => service("service").enabled() as service_enabled___service_
  @component = "service_started"
  if (skip_item_${report_data.canonified_directive_id} =~ error) => service("service").started() as service_started___service_
}
