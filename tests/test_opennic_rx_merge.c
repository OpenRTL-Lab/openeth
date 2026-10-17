// OpenRTL-source-header
// Organization: OpenRTL Lab (GitHub: OpenRTL-Lab)
// Organization introduction: Open hardware, from algorithms to RTL.
// Repository: OpenRTL-Lab/openeth
// Repository introduction: Reusable 1G Ethernet RTL and OpenNIC software.
// File: tests/test_opennic_rx_merge.c
// Website: https://openrtl.top/
// SPDX-License-Identifier: MIT
// OpenRTL-source-header-end
/* SPDX-License-Identifier: MIT */
#define OPENNIC_COPY_HOST_TEST
#include "../software/linux/v11/opennic_copy_rx.h"
#include <stdio.h>
#include <sys/mman.h>
#include <unistd.h>

static int guards(unsigned long *cases)
{
	long page = sysconf(_SC_PAGESIZE);
	u8 *a = mmap(NULL, page * 3, PROT_NONE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
	u8 *b = mmap(NULL, page * 3, PROT_NONE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
	_Alignas(16) u8 expected[2112], actual[2112];

	if (a == MAP_FAILED || b == MAP_FAILED ||
	    mprotect(a + page, page, PROT_READ | PROT_WRITE) ||
	    mprotect(b + page, page, PROT_READ | PROT_WRITE))
		return 1;
	for (long i = 0; i < page; ++i)
		a[page + i] = (u8)(i * 97);
	for (size_t n = 0; n <= 2048; ++n) {
		for (size_t off = 0; off < 16; ++off) {
			memset(actual, 0xa7, sizeof(actual));
			memset(expected, 0xa7, sizeof(expected));
			memcpy(expected + 16 + off, a + page * 2 - n, n);
			on_copy_from_dma_merge(actual + 16 + off, a + page * 2 - n, n);
			if (memcmp(actual, expected, sizeof(actual)))
				return 1;
			/* Destination ends exactly at protected page; source alignment varies. */
			memset(b + page, 0xa7, page);
			on_copy_from_dma_merge(b + page * 2 - n, a + page + off, n);
			if (memcmp(b + page * 2 - n, a + page + off, n))
				return 1;
			for (size_t i = 0; i < (size_t)page - n; ++i)
				if (b[page + i] != 0xa7)
					return 1;
			*cases += 2;
		}
		/* Neither source nor destination may access the protected prefix. */
		memset(b + page, 0xa7, page);
		on_copy_from_dma_merge(b + page, a + page, n);
		if (memcmp(b + page, a + page, n))
			return 1;
		++*cases;
	}
	return munmap(a, page * 3) || munmap(b, page * 3);
}

int main(void)
{
	_Alignas(16) u8 src[2112], dst[2112], expected[2112];
	unsigned long cases = 0, guarded = 0;

	for (size_t i = 0; i < sizeof(src); ++i)
		src[i] = (u8)((i * 113) ^ (i >> 3));
	for (size_t n = 0; n <= 2048; ++n)
		for (size_t a = 0; a < 16; ++a)
			for (size_t b = 0; b < 16; ++b) {
				memset(dst, 0xa7, sizeof(dst));
				memset(expected, 0xa7, sizeof(expected));
				memcpy(expected + 16 + a, src + 16 + b, n);
				on_copy_from_dma_merge(dst + 16 + a, src + 16 + b, n);
				if (memcmp(dst, expected, sizeof(dst))) {
					fprintf(stderr, "RX mismatch len=%zu dst=%zu src=%zu\n", n, a, b);
					return 1;
				}
				++cases;
			}
	if (guards(&guarded))
		return 2;
	dst[71] ^= 1;
	if (!memcmp(dst, expected, sizeof(dst)))
		return 3;
	printf("RX_MERGE_PASS cases=%lu protected_cases=%lu corruption_negative_control=PASS\n", cases, guarded);
	return 0;
}
