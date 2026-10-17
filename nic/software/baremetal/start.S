/* SPDX-License-Identifier: MIT */
.section .text.init
.globl _start
_start:
  csrr a0, mhartid
  bnez a0, 3f
  .option push
  .option norelax
  la gp, __global_pointer$
  .option pop
  la sp, _stack_top
  la t0, _bss_start
  la t1, _bss_end
1:
  bgeu t0, t1, 2f
  sd zero, 0(t0)
  addi t0, t0, 8
  j 1b
2:
  la t0, trap
  csrw mtvec, t0
  call main
3:
  wfi
  j 3b
trap:
  li a0, 127
  call finish
  j 3b
