// OpenRTL-source-header
// Organization: OpenRTL Lab (GitHub: OpenRTL-Lab)
// Organization introduction: Open hardware, from algorithms to RTL.
// Repository: OpenRTL-Lab/openeth
// Repository introduction: Reusable 1G Ethernet RTL and OpenNIC software.
// File: software/linux/v11/opennic_copy.h
// Website: https://openrtl.top/
// SPDX-License-Identifier: MIT
// OpenRTL-source-header-end
/* SPDX-License-Identifier: MIT */
/* Wide access on the aligned DMA side, byte-safe access on the skb side. */
#ifndef OPENNIC_COPY_H
#define OPENNIC_COPY_H
#ifdef OPENNIC_COPY_HOST_TEST
#include <stdint.h>
#include <stddef.h>
#include <string.h>
typedef uint64_t u64;
typedef uint8_t u8;
static inline u64 get_unaligned(const u64 *p)
{
	u64 v;
	memcpy(&v, p, sizeof(v));
	return v;
}
static inline void put_unaligned(u64 v, u64 *p)
{
	memcpy(p, &v, sizeof(v));
}
#else
#include <linux/types.h>
#include <linux/string.h>
#include <asm/unaligned.h>
#endif

static inline void on_copy_from_dma(void *dst, const void *src, size_t len)
{
	u8 *d = dst;
	const u8 *s = src;

	if ((unsigned long)s & 7) {
		memcpy(d, s, len);
		return;
	}
	while (len >= sizeof(u64)) {
		u64 v = *(const u64 *)s;
		put_unaligned(v, (u64 *)d);
		d += sizeof(v);
		s += sizeof(v);
		len -= sizeof(v);
	}
	memcpy(d, s, len);
}

static inline void on_copy_to_dma(void *dst, const void *src, size_t len)
{
	u8 *d = dst;
	const u8 *s = src;

	if ((unsigned long)d & 7) {
		memcpy(d, s, len);
		return;
	}
	while (len >= sizeof(u64)) {
		u64 v = get_unaligned((const u64 *)s);
		*(u64 *)d = v;
		d += sizeof(v);
		s += sizeof(v);
		len -= sizeof(v);
	}
	memcpy(d, s, len);
}

/* Non-overlapping skb -> DMA. Never read before src or beyond src + len. */
static inline void on_copy_to_dma_merge(void *dst, const void *src, size_t len)
{
#if __BYTE_ORDER__ == __ORDER_LITTLE_ENDIAN__
	u8 *d = dst;
	const u8 *s = src;
	unsigned int offset = (unsigned long)s & 7;

	if ((unsigned long)d & 7) {
		on_copy_to_dma(d, s, len);
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
	if (len >= sizeof(u64)) {
		/* Bootstrap without aligning down outside the valid source range. */
		*(u64 *)d = get_unaligned((const u64 *)s);
		d += sizeof(u64);
		s += sizeof(u64);
		len -= sizeof(u64);
	}
	if (len >= 16 - offset) {
		const u64 *words = (const u64 *)(s - offset);
		unsigned int shift = offset * 8;
		u64 lo = *words++;

		do {
			u64 hi = *words++;

			*(u64 *)d = (lo >> shift) | (hi << (64 - shift));
			lo = hi;
			d += sizeof(u64);
			s += sizeof(u64);
			len -= sizeof(u64);
		} while (len >= 16 - offset);
	}
	/* At most one last complete word plus a short tail, no lookahead. */
	on_copy_to_dma(d, s, len);
#else
	on_copy_to_dma(dst, src, len);
#endif
}
#endif
