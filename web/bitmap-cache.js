"use strict";

/** Caché GreedyDual-Size de bitmaps decodificados, limitada por bytes estimados. */
export class BitmapCache {
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

    /** Fija los bitmaps anunciados al servidor hasta que termine la VIEW. */
    pinMatching(imageId, sourceSizeBytes, zoomIndex, viewportWidth, viewportHeight) {
        const matches = [];
        for (const [key, entry] of this.entries) {
            const [id, size, zoom, width, height, column, row,
                chunkWidth, chunkHeight] = JSON.parse(key);
            if (id !== imageId || size !== sourceSizeBytes || zoom !== zoomIndex
                    || width !== viewportWidth || height !== viewportHeight) continue;
            if (matches.length === 128) break;
            entry.pins++;
            matches.push({key, column, row, width: chunkWidth, height: chunkHeight});
        }
        return matches;
    }

    release(keys) {
        for (const key of keys) {
            const entry = this.entries.get(key);
            if (entry && entry.pins > 0) entry.pins--;
        }
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
            if (!this.evictLowestPriority()) return false;
        }
        this.entries.set(key, {
            bitmap,
            bytes,
            cost,
            pins: 0,
            priority: this.age + cost / (bytes / 1048576)
        });
        this.currentBytes += bytes;
        return true;
    }

    evictLowestPriority() {
        let victimKey = null;
        let victim = null;
        for (const [key, entry] of this.entries) {
            if (entry.pins > 0) continue;
            if (!victim || entry.priority < victim.priority) {
                victimKey = key;
                victim = entry;
            }
        }
        if (!victim) return false;
        this.age = victim.priority;
        this.entries.delete(victimKey);
        this.currentBytes -= victim.bytes;
        victim.bitmap.close();
        this.evictions++;
        return true;
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
}
