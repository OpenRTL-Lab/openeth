# OpenRTL-source-header
# Organization: OpenRTL Lab (GitHub: OpenRTL-Lab)
# Organization introduction: Open hardware, from algorithms to RTL.
# Repository: OpenRTL-Lab/openeth
# Repository introduction: Reusable 1G Ethernet RTL and OpenNIC software.
# File: nic/software/baremetal/Makefile
# Website: https://openrtl.top/
# SPDX-License-Identifier: MIT
# OpenRTL-source-header-end
CROSS_COMPILE ?= riscv64-unknown-elf-
OUT ?= build

.PHONY: all
all: $(OUT)/loopback.elf

$(OUT)/loopback.elf: loopback.c start.S link.ld
	mkdir -p $(OUT)
	$(CROSS_COMPILE)gcc -march=rv64imac_zicsr -mabi=lp64 -mcmodel=medany \
	  -O2 -Wall -Wextra -ffreestanding -fno-builtin -fno-stack-protector \
	  -nostdlib -nostartfiles -static -Wl,--build-id=none -Wl,-T,link.ld \
	  start.S loopback.c -o $@
