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
  p0="skip_item_${report_data.canonified_directive_id}"
  p1="node.properties[skip][${report_data.directive_id}]"
  @component = "condition_from_variable_existence"
  condition(p0).from_variable_existence(p1) as condition_from_variable_existence_skip_item___report_data_canonified_directive_id_
  p0="${service}"
  @component = "service_enabled"
  if (skip_item_${report_data.canonified_directive_id} =~ error | skip_item_${report_data.canonified_directive_id} =~ repaired) => service(p0).enabled() as service_enabled___service_
  p0="${service}"
  @component = "service_started"
  if (skip_item_${report_data.canonified_directive_id} =~ false) => service(p0).started() as service_started___service_
}
