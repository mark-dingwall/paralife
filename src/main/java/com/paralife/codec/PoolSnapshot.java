package com.paralife.codec;

/** Per 15-SCHEMA.md §6.3.1 `p` block. */
public record PoolSnapshot(int pool, int maxPool) {
    public PoolSnapshot {
        if (pool < 0) throw new IllegalArgumentException("pool negative: " + pool);
        if (maxPool < 0) throw new IllegalArgumentException("maxPool negative: " + maxPool);
    }
}
