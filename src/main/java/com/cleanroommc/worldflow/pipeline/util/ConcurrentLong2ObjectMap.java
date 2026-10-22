package com.cleanroommc.worldflow.pipeline.util;

import it.unimi.dsi.fastutil.longs.AbstractLong2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectCollection;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectSet;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Weakly-consistent, thread-safe replacement for the plain fastutil map used by
 * {@code MapGenStructure}. Reads and writes never expose an iterator backed by a
 * concurrently-mutated table; snapshots are materialised on demand.
 */
public final class ConcurrentLong2ObjectMap<V> extends AbstractLong2ObjectMap<V> {

    private final ConcurrentHashMap<Long, V> backing = new ConcurrentHashMap<>();

    @Override
    public int size() {
        return backing.size();
    }

    @Override
    public boolean containsKey(long key) {
        return backing.containsKey(key);
    }

    @Override
    public V get(long key) {
        return backing.get(key);
    }

    @Override
    public V put(long key, V value) {
        return backing.put(key, value);
    }

    @Override
    public V remove(long key) {
        return backing.remove(key);
    }

    @Override
    public void clear() {
        backing.clear();
    }

    @Override
    public LongSet keySet() {
        LongOpenHashSet out = new LongOpenHashSet(backing.size());
        for (Long key : backing.keySet()) {
            out.add(key.longValue());
        }
        return out;
    }

    @Override
    public ObjectCollection<V> values() {
        return new ObjectArrayList<>(backing.values());
    }

    @Override
    public ObjectSet<Long2ObjectMap.Entry<V>> long2ObjectEntrySet() {
        ObjectOpenHashSet<Long2ObjectMap.Entry<V>> out = new ObjectOpenHashSet<>(backing.size());
        for (Map.Entry<Long, V> entry : backing.entrySet()) {
            out.add(new BasicEntry<>(entry.getKey().longValue(), entry.getValue()));
        }
        return out;
    }
}
