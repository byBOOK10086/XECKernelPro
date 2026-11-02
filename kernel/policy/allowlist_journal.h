/* SPDX-License-Identifier: GPL-2.0-only */
#ifndef KSU_ALLOWLIST_JOURNAL_H
#define KSU_ALLOWLIST_JOURNAL_H
/* Byte codec shared by kernel persistence and host tests. No external symbols.
 * Profiles remain the existing native ABI; all journal metadata is LE. */
#define KSU_J_HEADER 32U
#define KSU_J_FOOTER 24U
#define KSU_J_MAX_COUNT 65535U
#define KSU_J_MAGIC 0x4a55534bU
#define KSU_J_COMMIT 0x4355534bU
struct ksu_j_meta { unsigned long long generation; unsigned int count; };
static inline unsigned int ksu_j_get32(const unsigned char *p)
{
    return (unsigned int)p[0] | (unsigned int)p[1] << 8 |
           (unsigned int)p[2] << 16 | (unsigned int)p[3] << 24;
}
static inline void ksu_j_put32(unsigned char *p, unsigned int v)
{
    p[0] = v; p[1] = v >> 8; p[2] = v >> 16; p[3] = v >> 24;
}
static inline unsigned long long ksu_j_get64(const unsigned char *p)
{
    return ksu_j_get32(p) | (unsigned long long)ksu_j_get32(p + 4) << 32;
}
static inline void ksu_j_put64(unsigned char *p, unsigned long long v)
{
    ksu_j_put32(p, (unsigned int)v); ksu_j_put32(p + 4, (unsigned int)(v >> 32));
}
static inline unsigned int ksu_j_crc(const unsigned char *p, unsigned long n)
{
    unsigned int crc = ~0U;
    unsigned long i;
    unsigned int bit;
    for (i = 0; i < n; i++) {
        crc ^= p[i];
        for (bit = 0; bit < 8; bit++)
            crc = (crc >> 1) ^ (0xedb88320U & (0U - (crc & 1U)));
    }
    return ~crc;
}
static inline unsigned long ksu_j_length(unsigned int count, unsigned int record_size)
{
    /* Limit arithmetic to 32 bits as well, including on 64-bit hosts. */
    if (count > KSU_J_MAX_COUNT || !record_size ||
        record_size > (0xffffffffU - KSU_J_HEADER - KSU_J_FOOTER) / (count ? count : 1))
        return 0;
    return KSU_J_HEADER + (unsigned long)count * record_size + KSU_J_FOOTER;
}
static inline int ksu_j_header(const unsigned char *p, unsigned int record_size,
                               struct ksu_j_meta *meta)
{
    if (ksu_j_get32(p) != KSU_J_MAGIC || ksu_j_get32(p + 4) != 1 ||
        !ksu_j_get64(p + 8) || ksu_j_get32(p + 20) != record_size ||
        !ksu_j_length(ksu_j_get32(p + 16), record_size) ||
        ksu_j_get32(p + 28) != ksu_j_crc(p, 28))
        return -1;
    meta->generation = ksu_j_get64(p + 8);
    meta->count = ksu_j_get32(p + 16);
    return 0;
}
/* Caller allocated length(count, size) and filled its payload before sealing. */
static inline int ksu_j_seal(unsigned char *p, unsigned int count, unsigned int size,
                             unsigned long long generation)
{
    unsigned long length = ksu_j_length(count, size);
    unsigned char *tail;
    if (!length || !generation)
        return -1;
    ksu_j_put32(p, KSU_J_MAGIC); ksu_j_put32(p + 4, 1);
    ksu_j_put64(p + 8, generation); ksu_j_put32(p + 16, count);
    ksu_j_put32(p + 20, size);
    ksu_j_put32(p + 24, ksu_j_crc(p + KSU_J_HEADER, length - KSU_J_HEADER - KSU_J_FOOTER));
    ksu_j_put32(p + 28, ksu_j_crc(p, 28));
    tail = p + length - KSU_J_FOOTER;
    ksu_j_put32(tail, KSU_J_COMMIT); ksu_j_put32(tail + 4, 1);
    ksu_j_put64(tail + 8, generation); ksu_j_put32(tail + 16, count);
    ksu_j_put32(tail + 20, ksu_j_crc(p, length - KSU_J_FOOTER));
    return 0;
}
static inline int ksu_j_validate(const unsigned char *p, unsigned long length,
                                 unsigned int size, struct ksu_j_meta *meta)
{
    struct ksu_j_meta m;
    const unsigned char *tail;
    if (length < KSU_J_HEADER + KSU_J_FOOTER || ksu_j_header(p, size, &m) ||
        length != ksu_j_length(m.count, size))
        return -1;
    tail = p + length - KSU_J_FOOTER;
    if (ksu_j_get32(p + 24) != ksu_j_crc(p + KSU_J_HEADER, length - KSU_J_HEADER - KSU_J_FOOTER) ||
        ksu_j_get32(tail) != KSU_J_COMMIT || ksu_j_get32(tail + 4) != 1 ||
        ksu_j_get64(tail + 8) != m.generation || ksu_j_get32(tail + 16) != m.count ||
        ksu_j_get32(tail + 20) != ksu_j_crc(p, length - KSU_J_FOOTER))
        return -1;
    *meta = m;
    return 0;
}
#endif
