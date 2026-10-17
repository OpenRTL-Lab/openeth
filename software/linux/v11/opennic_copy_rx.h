// OpenRTL-source-header
// Organization: OpenRTL Lab (GitHub: OpenRTL-Lab)
// Organization introduction: Open hardware, from algorithms to RTL.
// Repository: OpenRTL-Lab/openeth
// Repository introduction: Reusable 1G Ethernet RTL and OpenNIC software.
// File: software/linux/v11/opennic_copy_rx.h
// Website: https://openrtl.top/
// SPDX-License-Identifier: MIT
// OpenRTL-source-header-end
/* SPDX-License-Identifier: MIT */
/* Experimental RX helper: default disabled in the dev4 driver. */
#ifndef OPENNIC_COPY_RX_H
#define OPENNIC_COPY_RX_H
#include "opennic_copy.h"

/* Non-overlapping aligned DMA -> cached skb, without changing packet alignment. */
static inline void on_copy_from_dma_merge(void *dst, const void *src, size_t len)
{
#if __BYTE_ORDER__ == __ORDER_LITTLE_ENDIAN__
	u8 *d = dst;
	const u8 *s = src;
	unsigned int offset = (unsigned long)d & 7;

	if (((unsigned long)s & 7) || len < sizeof(u64)) {
		on_copy_from_dma(d, s, len);
		return;
	}
	if (!offset) {
		while (len >= sizeof(u64)) {
			*(u64 *)d = *(const u64 *)s;
			d += sizeof(u64);
			s += sizeof(u64);
			len -= sizeof(u64);
		}
		memcpy(d, s, len);
		return;
	}
	{
		unsigned int prefix = 8 - offset;
		unsigned int shift = prefix * 8;
		u64 lo = *(const u64 *)s;
		const u64 *words = (const u64 *)(s + 8);

		for (unsigned int i = 0; i < prefix; ++i)
			d[i] = (u8)(lo >> (i * 8));
		d += prefix;
		s += prefix;
		len -= prefix;
		while (len >= 16 - prefix) {
			u64 hi = *words++;

			*(u64 *)d = (lo >> shift) | (hi << (64 - shift));
			lo = hi;
			d += sizeof(u64);
			s += sizeof(u64);
			len -= sizeof(u64);
		}
	}
	on_copy_from_dma(d, s, len);
#else
	on_copy_from_dma(dst, src, len);
#endif
}
#endif
