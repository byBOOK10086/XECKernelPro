// SPDX-License-Identifier: GPL-2.0-only
/*
 * Minimal SHA-256 for the superkey guard (kernel/policy/superkey.c).
 * Standard FIPS 180-4 implementation, no external dependencies — a module
 * must not depend on CONFIG_CRYPTO being available on every target kernel.
 *
 * Known-answer check (useful when porting): sha256("abc") =
 * ba7816bf 8f01cfea 414140de 5dae2223 b00361a3 96177a9c b410ff61 f20015ad.
 */
#include "superkey.h"

#include <linux/string.h>

static const u32 K[64] = {
    0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
    0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
    0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
    0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
    0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
    0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
    0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
    0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2,
};

#define ROTR(x, n) (((x) >> (n)) | ((x) << (32 - (n))))

static void sha256_block(u32 h[8], const u8 *p)
{
    u32 w[64];
    u32 a, b, c, d, e, f, g, hh;
    int i;

    for (i = 0; i < 16; i++)
        w[i] = ((u32)p[i * 4] << 24) | ((u32)p[i * 4 + 1] << 16) | ((u32)p[i * 4 + 2] << 8) | p[i * 4 + 3];
    for (i = 16; i < 64; i++) {
        u32 s0 = ROTR(w[i - 15], 7) ^ ROTR(w[i - 15], 18) ^ (w[i - 15] >> 3);
        u32 s1 = ROTR(w[i - 2], 17) ^ ROTR(w[i - 2], 19) ^ (w[i - 2] >> 10);
        w[i] = w[i - 16] + s0 + w[i - 7] + s1;
    }

    a = h[0]; b = h[1]; c = h[2]; d = h[3];
    e = h[4]; f = h[5]; g = h[6]; hh = h[7];

    for (i = 0; i < 64; i++) {
        u32 S1 = ROTR(e, 6) ^ ROTR(e, 11) ^ ROTR(e, 25);
        u32 ch = (e & f) ^ (~e & g);
        u32 t1 = hh + S1 + ch + K[i] + w[i];
        u32 S0 = ROTR(a, 2) ^ ROTR(a, 13) ^ ROTR(a, 22);
        u32 maj = (a & b) ^ (a & c) ^ (b & c);
        u32 t2 = S0 + maj;

        hh = g; g = f; f = e; e = d + t1;
        d = c; c = b; b = a; a = t1 + t2;
    }

    h[0] += a; h[1] += b; h[2] += c; h[3] += d;
    h[4] += e; h[5] += f; h[6] += g; h[7] += hh;
}

void ksu_sha256(const u8 *data, size_t len, u8 out[32])
{
    u32 h[8] = {
        0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a,
        0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19,
    };
    u8 tail[128];
    u64 bitlen = (u64)len * 8;
    size_t rem, tail_len;
    int i;

    while (len >= 64) {
        sha256_block(h, data);
        data += 64;
        len -= 64;
    }

    // tail = remaining bytes || 0x80 || zeros || 8-byte big-endian bit length,
    // padded to a multiple of 64 (56-boundary rule => one or two blocks).
    rem = len;
    memset(tail, 0, sizeof(tail));
    memcpy(tail, data, rem);
    tail[rem] = 0x80;
    tail_len = (rem < 56) ? 64 : 128;
    for (i = 0; i < 8; i++)
        tail[tail_len - 8 + i] = (u8)(bitlen >> (56 - 8 * i));

    sha256_block(h, tail);
    if (tail_len == 128)
        sha256_block(h, tail + 64);

    for (i = 0; i < 8; i++) {
        out[i * 4] = (u8)(h[i] >> 24);
        out[i * 4 + 1] = (u8)(h[i] >> 16);
        out[i * 4 + 2] = (u8)(h[i] >> 8);
        out[i * 4 + 3] = (u8)(h[i]);
    }
}
