// OpenRTL-source-header
// Organization: OpenRTL Lab (GitHub: OpenRTL-Lab)
// Organization introduction: Open hardware, from algorithms to RTL.
// Repository: OpenRTL-Lab/openeth
// Repository introduction: Reusable 1G Ethernet RTL and OpenNIC software.
// File: nic/software/baremetal/loopback.c
// Website: https://openrtl.top/
// SPDX-License-Identifier: MIT
// OpenRTL-source-header-end
// SPDX-License-Identifier: MIT
#include <stdint.h>

volatile uint64_t tohost __attribute__((section(".tohost"), aligned(64)));
volatile uint64_t fromhost __attribute__((section(".tohost"), aligned(64)));
static volatile uint8_t tx[16][2112] __attribute__((aligned(64)));
static volatile uint8_t rx[16][2112] __attribute__((aligned(64)));
static const unsigned lengths[] = {1, 7, 8, 9, 63, 64, 65, 511, 512, 513, 1514, 2048};

static inline void fence(void) { __asm__ volatile("fence iorw, iorw" ::: "memory"); }
static inline uint32_t rd(unsigned reg) { return *(volatile uint32_t *)(uintptr_t)(0x10016000 + reg); }
static inline void wr(unsigned reg, uint32_t value)
{
	fence();
	*(volatile uint32_t *)(uintptr_t)(0x10016000 + reg) = value;
	fence();
}
static void putchar_uart(char c)
{
	volatile uint32_t *uart = (volatile uint32_t *)(uintptr_t)0x10020000;
	while (uart[0] & 0x80000000u) {}
	uart[0] = (uint8_t)c;
}
static void text(const char *s) { while (*s) putchar_uart(*s++); }
void finish(unsigned code)
{
	if (code) text("OPENNIC_SOC_FAIL\n");
	else text("OPENNIC_SOC_PASS frames=256 bytecheck=1 guards=1 irq=1\n");
	/* Drain UART before HTIF terminates the simulator. */
	for (volatile unsigned i = 0; i < 200000; i++) {}
	fence();
	tohost = ((uint64_t)code << 1) | 1;
	for (;;) __asm__ volatile("wfi");
}
static void require(int ok, unsigned code) { if (!ok) finish(code); }
static void wait_count(unsigned reg, unsigned count, unsigned code)
{
	for (unsigned n = 0; n < 2000000; n++) {
		if (rd(reg) == count) return;
	}
	finish(code);
}
static uint8_t byte(unsigned frame, unsigned offset)
{
	return (uint8_t)((frame * 31u + offset * 17u) ^ (offset >> 3));
}
static void post(unsigned bank, volatile uint8_t *buffer, unsigned cookie, unsigned len)
{
	uintptr_t addr = (uintptr_t)buffer;
	wr(bank, (uint32_t)addr); wr(bank + 4, (uint32_t)(addr >> 32));
	wr(bank + 8, (cookie << 16) | len); wr(bank + 12, 1);
}
int main(void)
{
	volatile uint32_t *plic_enable = (volatile uint32_t *)(uintptr_t)0x0c002000;
	volatile uint32_t *plic_context = (volatile uint32_t *)(uintptr_t)0x0c200000;
	volatile uint32_t *plic = (volatile uint32_t *)(uintptr_t)0x0c000000;
	volatile uint32_t *uart = (volatile uint32_t *)(uintptr_t)0x10020000;
	/* 保留平台按50MHz/115200初始化的分频；不可用任意高速分频覆盖。 */
	uart[2] = 1;
	text("OPENNIC_SOC_START frames=256\n");
	require(rd(0) == 0x4f4e4943 && rd(4) == 0x10000, 1);
	wr(8, 0); wr(8, 2); wr(0x10, 3);
	plic[3] = 1; plic[4] = 1; *plic_enable = 24; plic_context[0] = 0;
	wr(8, 1);
	for (unsigned batch = 0; batch < 16; batch++) {
		for (unsigned slot = 0; slot < 16; slot++) {
			unsigned frame = batch * 16 + slot;
			unsigned len = lengths[frame % 12];
			for (unsigned j = 0; j < 2112; j++) {
				tx[slot][j] = j < len ? byte(frame, j) : 0xcc;
				rx[slot][j] = 0xa5;
			}
			post(0x30, rx[slot], frame, 2048);
		}
		for (unsigned slot = 0; slot < 16; slot++) {
			unsigned frame = batch * 16 + slot;
			post(0x20, tx[slot], frame, lengths[frame % 12]);
		}
		wait_count(0x44, 16, 2); wait_count(0x4c, 16, 3);
		require((rd(0x14) & 3) == 3, 4);
		/* Both sources pending before either completion queue is popped. */
		unsigned irq0 = plic_context[1], irq1 = plic_context[1];
		require((irq0 == 3 && irq1 == 4) || (irq0 == 4 && irq1 == 3), 5);
		for (unsigned slot = 0; slot < 16; slot++) {
			unsigned frame = batch * 16 + slot;
			unsigned len = lengths[frame % 12];
			require(rd(0x40) == frame << 16, 6);
			require(rd(0x50) == 0, 7);
			require(rd(0x48) == (frame << 16 | len << 4), 8);
			fence();
			for (unsigned j = 0; j < 2112; j++) {
				require(rx[slot][j] == (j < len ? byte(frame, j) : 0xa5), 9);
				require(tx[slot][j] == (j < len ? byte(frame, j) : 0xcc), 10);
			}
		}
		plic_context[1] = irq0; plic_context[1] = irq1;
		require(rd(0x44) == 0 && rd(0x4c) == 0 && rd(0x14) == 0, 11);
		if ((batch & 3) == 3) text("OPENNIC_SOC_PROGRESS batches+=4\n");
	}
	wr(8, 0);
	for (unsigned n = 0; n < 1000000 && (rd(0x0c) & 6); n++) {}
	require(!(rd(0x0c) & 6) && rd(0x58) == 0 && rd(0x5c) == 0, 12);
	wr(8, 2);
	finish(0);
	return 0;
}
