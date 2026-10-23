package com.cleanroommc.worldflow.pipeline.isolation;

import net.minecraft.entity.Entity;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Iterator;
import java.util.ListIterator;
import java.util.Spliterator;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * Drop-in replacement for {@code World.loadedEntityList} that tolerates the
 * parallel-generation workload: the server thread adds/removes entities while
 * feature workers read the list (e.g. NuclearCraft's {@code World.countEntities}
 * during a spawn check). Mutations are synchronized and every iterator is a
 * snapshot, so readers never see a {@link java.util.ConcurrentModificationException}
 * or a half-resized backing array.
 */
public class WorldFlowEntityList extends ArrayList<Entity> {

    public WorldFlowEntityList() {
        super();
    }

    public WorldFlowEntityList(int initialCapacity) {
        super(initialCapacity);
    }

    @Override
    public synchronized boolean add(Entity entity) {
        return super.add(entity);
    }

    @Override
    public synchronized void add(int index, Entity element) {
        super.add(index, element);
    }

    @Override
    public synchronized boolean addAll(Collection<? extends Entity> c) {
        return super.addAll(c);
    }

    @Override
    public synchronized boolean addAll(int index, Collection<? extends Entity> c) {
        return super.addAll(index, c);
    }

    @Override
    public synchronized boolean remove(Object o) {
        return super.remove(o);
    }

    @Override
    public synchronized Entity remove(int index) {
        return super.remove(index);
    }

    @Override
    public synchronized boolean removeAll(Collection<?> c) {
        return super.removeAll(c);
    }

    @Override
    public synchronized boolean retainAll(Collection<?> c) {
        return super.retainAll(c);
    }

    @Override
    public synchronized boolean removeIf(Predicate<? super Entity> filter) {
        return super.removeIf(filter);
    }

    @Override
    public synchronized void clear() {
        super.clear();
    }

    @Override
    public synchronized Entity set(int index, Entity element) {
        return super.set(index, element);
    }

    @Override
    public synchronized int size() {
        return super.size();
    }

    @Override
    public synchronized boolean isEmpty() {
        return super.isEmpty();
    }

    @Override
    public synchronized Entity get(int index) {
        return super.get(index);
    }

    @Override
    public synchronized boolean contains(Object o) {
        return super.contains(o);
    }

    @Override
    public synchronized int indexOf(Object o) {
        return super.indexOf(o);
    }

    @Override
    public synchronized Object[] toArray() {
        return super.toArray();
    }

    @Override
    public synchronized <T> T[] toArray(T[] a) {
        return super.toArray(a);
    }

    @Override
    public synchronized void sort(Comparator<? super Entity> c) {
        super.sort(c);
    }

    @Override
    public synchronized Iterator<Entity> iterator() {
        return new ArrayList<>(this).iterator();
    }

    @Override
    public synchronized ListIterator<Entity> listIterator() {
        return new ArrayList<>(this).listIterator();
    }

    @Override
    public synchronized ListIterator<Entity> listIterator(int index) {
        return new ArrayList<>(this).listIterator(index);
    }

    @Override
    public synchronized void forEach(Consumer<? super Entity> action) {
        new ArrayList<>(this).forEach(action);
    }

    @Override
    public synchronized Spliterator<Entity> spliterator() {
        return new ArrayList<>(this).spliterator();
    }

    @Override
    public synchronized Stream<Entity> stream() {
        return new ArrayList<>(this).stream();
    }

    @Override
    public synchronized Stream<Entity> parallelStream() {
        return new ArrayList<>(this).parallelStream();
    }

    /** Snapshot copy under the list monitor. */
    public synchronized java.util.ArrayList<Entity> worldflow$snapshot() {
        return new java.util.ArrayList<>(this);
    }
}
