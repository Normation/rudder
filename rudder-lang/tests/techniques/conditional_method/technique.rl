# This file has been generated with rltranslate
@format=0
@name="conditional method"
@description="one method with several conditons"
@version = 0
@category = "ncf_techniques"
@parameters=[]

resource conditional_method()

conditional_method state technique() {
  @component = "HTTP request check status with headers"
  if debian => http("GET").request_check_status_headers("/myurl","200","") as http_request_check_status_headers__myurl
}
