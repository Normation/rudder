package com.normation.inventory.domain

import com.normation.utils.EnumLaws
import zio.test.Spec
import zio.test.ZIOSpecDefault

object VmTypeTest extends ZIOSpecDefault {
  private val validNames =
    Seq("unknown", "vbox", "vmware", "qemu", "xen", "hyperv", "virtuozzo", "openvz", "lxc")
  override def spec: Spec[Any, Nothing] = EnumLaws.laws(VmType, validNames)
}
