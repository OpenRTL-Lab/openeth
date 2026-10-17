// OpenRTL-source-header
// Organization: OpenRTL Lab (GitHub: OpenRTL-Lab)
// Organization introduction: Open hardware, from algorithms to RTL.
// Repository: OpenRTL-Lab/openeth
// Repository introduction: Reusable 1G Ethernet RTL and OpenNIC software.
// File: tests/test_opennic_copy.c
// Website: https://openrtl.top/
// SPDX-License-Identifier: MIT
// OpenRTL-source-header-end
/* SPDX-License-Identifier: MIT */
#define OPENNIC_COPY_HOST_TEST
#include "../software/linux/v11/opennic_copy.h"
#include <stdio.h>
#include <stdlib.h>
#include <sys/mman.h>
#include <unistd.h>

static int guarded_source_tail(void)
{
	long page = sysconf(_SC_PAGESIZE);
	unsigned char *map = mmap(NULL, page * 3, PROT_NONE,
				 MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
	_Alignas(16) unsigned char dst[2112], expected[2112];

	if (map == MAP_FAILED || mprotect(map + page, page, PROT_READ | PROT_WRITE))
		return 1;
	for (long i = 0; i < page; ++i)
		map[page + i] = (unsigned char)(i * 97);
	for (size_t n = 0; n <= 2048; ++n) {
		/* Exact readable end: any speculative lookahead crosses PROT_NONE. */
		const unsigned char *source = map + page * 2 - n;

		memset(dst, 0xa7, sizeof(dst));
		memset(expected, 0xa7, sizeof(expected));
		memcpy(expected + 16, source, n);
		on_copy_to_dma_merge(dst + 16, source, n);
		if (memcmp(dst, expected, sizeof(dst)))
			return 1;
	}
	/* Aligned source starts immediately after an inaccessible prefix. */
	for (size_t n = 0; n <= 2048; ++n) {
		memset(dst, 0xa7, sizeof(dst));
		memset(expected, 0xa7, sizeof(expected));
		memcpy(expected + 16, map + page, n);
		on_copy_to_dma_merge(dst + 16, map + page, n);
		if (memcmp(dst, expected, sizeof(dst)))
			return 1;
	}
	return munmap(map, page * 3) != 0;
}

int main(void)
{
	_Alignas(16) unsigned char src[2112], dst[2112], expected[2112];
	unsigned long cases = 0;
	for (size_t i = 0; i < sizeof(src); ++i)
		src[i] = (unsigned char)((i * 113) ^ (i >> 3));
	for (size_t n = 0; n <= 2048; ++n) {
		for (size_t a = 0; a < 16; ++a) {
			for (size_t b = 0; b < 16; ++b) {
				for (int dir = 0; dir < 3; ++dir) {
					memset(dst, 0xa7, sizeof(dst));
					memset(expected, 0xa7, sizeof(expected));
					memcpy(expected + 16 + a, src + 16 + b, n);
					if (dir == 2)
						on_copy_to_dma_merge(dst + 16 + a, src + 16 + b, n);
					else if (dir)
						on_copy_to_dma(dst + 16 + a, src + 16 + b, n);
					else
						on_copy_from_dma(dst + 16 + a, src + 16 + b, n);
					if (memcmp(dst, expected, sizeof(dst))) {
						fprintf(stderr, "copy mismatch n=%zu dst=%zu src=%zu dir=%d\n", n, a, b, dir);
						return 1;
					}
					++cases;
				}
			}
		}
	}
	if (guarded_source_tail())
		return 3;
	dst[71] ^= 1;
	if (!memcmp(dst, expected, sizeof(dst)))
		return 2;
	printf("COPY_PASS cases=%lu guard_page_lengths=4098 corruption_negative_control=PASS\n", cases);
	return 0;
}
