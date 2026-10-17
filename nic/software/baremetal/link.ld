/* SPDX-License-Identifier: MIT */
OUTPUT_ARCH(riscv)
ENTRY(_start)
SECTIONS {
  . = 0x80000000;
  .text : { *(.text.init) *(.text*) }
  .rodata : { *(.rodata*) }
  .data : { *(.data*) }
  .sdata : { __global_pointer$ = . + 0x800; *(.sdata*) }
  . = ALIGN(64);
  .tohost : { *(.tohost) }
  . = ALIGN(64);
  _bss_start = .;
  .bss : { *(.bss*) *(.sbss*) *(COMMON) }
  . = ALIGN(8);
  _bss_end = .;
  . += 16384;
  _stack_top = .;
}
