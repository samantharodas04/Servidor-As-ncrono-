"use strict";

/** Caché GreedyDual-Size de bitmaps decodificados, limitada por bytes estimados. */
window.BitmapCache = class BitmapCache {
    constructor(maximumBytes) {
        if (!Number.isSafeInteger(maximumBytes) || maximumBytes <= 0) {
            throw new Error("El límite de caché debe ser positivo");
        }
        this.maximumBytes = maximumBytes;
        this.currentBytes = 0;
        this.age = 0;
        this.entries = new Map();
        this.hits = 0;
        this.misses = 0;
        this.evictions = 0;
    }

    get(key) {
        const entry = this.entries.get(key);
        if (!entry) {
            this.misses++;
            return null;
        }
        this.hits++;
        entry.priority = this.age + entry.cost / (entry.bytes / 1048576);
        return entry.bitmap;
    }

    /** Devuelve true si conserva el bitmap; el llamador cierra los rechazados. */
    put(key, bitmap, decodeMillis) {
        const bytes = bitmap.width * bitmap.height * 4;
        if (!Number.isSafeInteger(bytes) || bytes <= 0
                || bytes > this.maximumBytes || this.entries.has(key)) {
            return false;
        }
        const cost = Math.max(1, Number.isFinite(decodeMillis) ? decodeMillis : 1);
        while (this.currentBytes + bytes > this.maximumBytes) {
            this.evictLowestPriority();
        }
        this.entries.set(key, {
            bitmap,
            bytes,
            cost,
            priority: this.age + cost / (bytes / 1048576)
        });
        this.currentBytes += bytes;
        return true;
    }

    evictLowestPriority() {
        let victimKey = null;
        let victim = null;
        for (const [key, entry] of this.entries) {
            if (!victim || entry.priority < victim.priority) {
                victimKey = key;
                victim = entry;
            }
        }
        if (!victim) throw new Error("La caché no tiene una entrada para expulsar");
        this.age = victim.priority;
        this.entries.delete(victimKey);
        this.currentBytes -= victim.bytes;
        victim.bitmap.close();
        this.evictions++;
    }

    clear() {
        for (const entry of this.entries.values()) entry.bitmap.close();
        this.entries.clear();
        this.currentBytes = 0;
        this.age = 0;
        this.hits = 0;
        this.misses = 0;
        this.evictions = 0;
    }

    get size() {
        return this.entries.size;
    }
};
