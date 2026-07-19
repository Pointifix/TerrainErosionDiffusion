package com.github.xandergos.terraindiffusionmc.erosion.soilmachine;

import java.util.ArrayDeque;

/**
 * Memory pool for Sec nodes. Maps to secpool in layermap.h.
 */
public class SecPool {

    private final Sec[] pool;
    private final ArrayDeque<Sec> free;

    public SecPool(int capacity) {
        pool = new Sec[capacity];
        free = new ArrayDeque<>(capacity);
        for (int i = 0; i < capacity; i++) {
            pool[i] = new Sec();
            free.push(pool[i]);
        }
    }

    /** Get a Sec from the pool, constructed with given size and type. */
    public Sec get(double size, int type) {
        if (free.isEmpty()) {
            System.err.println("Memory Pool Out-Of-Elements");
            return null;
        }
        Sec e = free.pop();
        e.reset();
        e.size = size;
        e.type = type;
        return e;
    }

    /** Return a Sec to the pool. */
    public void unget(Sec e) {
        if (e == null) return;
        e.reset();
        free.push(e);
    }

    /** Reset all elements back to the free pool. */
    public void reset() {
        free.clear();
        for (Sec s : pool) {
            s.reset();
            free.push(s);
        }
    }

    public int freeCount() { return free.size(); }
}
