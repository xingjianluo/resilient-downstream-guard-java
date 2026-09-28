package com.github.xingjianluo.resilientdownstreamguardjava.guard;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 有容量上限、带 TTL 的 LRU 缓存。
 *
 * 用于短期保留同业务键的已完成结果：超时后的重试在保留窗口内拿到同一结果，
 * 从而下游副作用最多一次；容量（LRU 淘汰）与 TTL（惰性 + 主动清理）双重约束，
 * 保证长期运行不累积无用对象。
 */
public final class BoundedTtlCache<K, V> {

    private final int maxEntries;
    private final long ttlMillis;
    private final Clock clock;
    private final LruMap<K, CacheEntry<V>> map;

    public BoundedTtlCache(int maxEntries, long ttlMillis, Clock clock) {
        if (maxEntries <= 0) {
            throw new IllegalArgumentException("maxEntries must be positive");
        }
        this.maxEntries = maxEntries;
        this.ttlMillis = ttlMillis;
        this.clock = clock;
        this.map = new LruMap<K, CacheEntry<V>>(maxEntries);
    }

    public synchronized void put(K key, V value) {
        map.put(key, new CacheEntry<>(value, clock.nowMillis() + ttlMillis));
    }

    /** 读取未过期条目；不存在或已过期返回 null（过期顺手移除）。 */
    public synchronized V getIfFresh(K key) {
        CacheEntry<V> entry = map.get(key);
        if (entry == null) {
            return null;
        }
        if (clock.nowMillis() > entry.expireAtMillis) {
            map.remove(key);
            return null;
        }
        return entry.value;
    }

    public synchronized int size() {
        return map.size();
    }

    /** 主动清理所有过期条目。 */
    public synchronized void purgeExpired() {
        long now = clock.nowMillis();
        Iterator<Map.Entry<K, CacheEntry<V>>> it = map.entrySet().iterator();
        while (it.hasNext()) {
            if (now > it.next().getValue().expireAtMillis) {
                it.remove();
            }
        }
    }

    public synchronized void clear() {
        map.clear();
    }

    /** 缓存值包装：值 + 过期时间。 */
    private static final class CacheEntry<V> {
        final V value;
        final long expireAtMillis;

        CacheEntry(V value, long expireAtMillis) {
            this.value = value;
            this.expireAtMillis = expireAtMillis;
        }
    }

    /** accessOrder 的 LinkedHashMap，超过上限时淘汰最久未使用条目。 */
    private static final class LruMap<K, V> extends LinkedHashMap<K, V> {
        private final int maxEntries;

        LruMap(int maxEntries) {
            super(16, 0.75f, true);
            this.maxEntries = maxEntries;
        }

        @Override
        protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
            return size() > maxEntries;
        }
    }
}
