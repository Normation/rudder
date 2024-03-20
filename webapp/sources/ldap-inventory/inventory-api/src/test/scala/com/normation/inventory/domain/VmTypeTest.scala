package com.normation.inventory.domain

import com.normation.utils.EnumLaws
import zio.test.Spec
import zio.test.ZIOSpecDefault

object VmTypeTest extends ZIOSpecDefault {
  private val validNames =
    Seq("unknown", "solariszone", "vbox", "vmware", "qemu", "xen", "aixlpar", "hyperv", "bsdjail", "virtuozzo", "openvz", "lxc")
  override def spec: Spec[Any, Nothing] = EnumLaws.laws(VmType, validNames)
}
