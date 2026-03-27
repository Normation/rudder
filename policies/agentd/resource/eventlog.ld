OUTPUT_FORMAT(pei-x86-64)
SECTIONS
{
  . = SIZEOF_HEADERS;
  . = ALIGN(__section_alignment__);
  .rsrc __image_base__ + __section_alignment__ : ALIGN(4)
  {
    KEEP (*(.rsrc))
    KEEP (*(.rsrc$*))
  }
  /DISCARD/ : { *(*) }
}
