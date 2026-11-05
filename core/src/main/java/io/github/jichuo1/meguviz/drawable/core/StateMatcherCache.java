package io.github.jichuo1.meguviz.drawable.core;

import io.github.jichuo1.meguviz.drawable.model.StateColorList;
import io.github.jichuo1.meguviz.drawable.model.StateSpec;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Process-wide, bounded, LRU cache of compiled state tables.
 *
 * <p>Only the rule <em>conditions</em> matter to a compiled table, so the key is the ordered list
 * of {@link StateSpec}s and deliberately excludes colors: two lists with the same conditions but
 * different colors share one table.
 *
 * <p>Contract:
 * <ul>
 *   <li>Keys and values are immutable; no Drawable, Paint, Path, Rect or callback is retained.</li>
 *   <li>Fallback matchers (which only delegate to direct matching) are never cached.</li>
 *   <li>Table construction happens outside the lock; the lock only guards map operations.</li>
 *   <li>Lookup is expected once per drawable instance, never per query. A content-keyed
 *       {@code HashMap} in the query path is slower than direct matching for large rule lists.</li>
 * </ul>
 */
public final class StateMatcherCache {
    /** Default cap: about 1024 small tables, or 8 worst-case K=16 tables. */
    public static final long DEFAULT_MAX_WEIGHT = 1L << 20;

    /** Distinct-ID counts above this need an explicit weight budget to be cached. */
    private static final int ALWAYS_CACHE_MAX_IDS = 8;

    private static final StateMatcherCache SHARED = new StateMatcherCache(DEFAULT_MAX_WEIGHT);

    private final long maxWeight;
    private final LinkedHashMap<Key, Entry> cache = new LinkedHashMap<Key, Entry>(16, 0.75f, true);
    private long weight;

    /** The process-wide cache used by {@code RecipeDrawable}. */
    public static StateMatcherCache shared() { return SHARED; }

    public StateMatcherCache(long maxWeight) {
        if (maxWeight < 1) throw new IllegalArgumentException("maxWeight must be positive");
        this.maxWeight = maxWeight;
    }

    /**
     * Returns a compiled matcher for {@code rules}, or {@code null} when the rules are not worth
     * compiling (too many distinct IDs, over the preparation budget, or the resulting table would
     * exceed the remaining weight). {@code null} means: keep using direct matching.
     */
    public StateMatcher.PreparedMatcher acquire(StateColorList rules) {
        if (rules == null) throw new NullPointerException("rules");
        Key key = new Key(rules);

        synchronized (this) {
            Entry hit = cache.get(key);
            if (hit != null) return hit.matcher;
        }

        // Build outside the lock: this can take ~2 ms at K=16/R=64.
        StateMatcher.PreparedMatcher built = StateMatcher.prepare(rules);
        int ids = tableIdCount(built);
        if (ids < 0) return null; // delegating matcher: no benefit, and it retains the rules
        long cost = tableBytes(ids) + key.retainedBytes();
        if (ids > ALWAYS_CACHE_MAX_IDS && cost > maxWeight / 8) return null;

        synchronized (this) {
            Entry existing = cache.get(key);
            if (existing != null) return existing.matcher; // lost the race; keep the winner
            cache.put(key, new Entry(built, cost));
            weight += cost;
            Iterator<Map.Entry<Key, Entry>> eldest = cache.entrySet().iterator();
            while (eldest.hasNext() && weight > maxWeight) {
                Map.Entry<Key, Entry> entry = eldest.next();
                eldest.remove();
                weight -= entry.getValue().cost;
            }
            return built;
        }
    }

    public synchronized long weight() { return weight; }

    public synchronized int size() { return cache.size(); }

    /** Drops every cached table; used by tests and by hosts that release large resources. */
    public synchronized void clear() {
        cache.clear();
        weight = 0;
    }

    /** Distinct-ID count of a compiled table, or -1 when the matcher delegates to direct matching. */
    private static int tableIdCount(StateMatcher.PreparedMatcher matcher) {
        return matcher.tableIdCount();
    }

    private static long tableBytes(int ids) {
        return 2L * (1L << ids);
    }

    private static final class Entry {
        final StateMatcher.PreparedMatcher matcher;
        final long cost;

        Entry(StateMatcher.PreparedMatcher matcher, long cost) {
            this.matcher = matcher;
            this.cost = cost;
        }
    }

    /**
     * Content key over the ordered rule conditions. The hash is computed once at construction so
     * a lookup never re-walks every condition.
     */
    private static final class Key {
        private final StateSpec[] specs;
        private final int hash;

        Key(StateColorList rules) {
            int count = rules.size();
            StateSpec[] copy = new StateSpec[count];
            int h = 1;
            for (int i = 0; i < count; i++) {
                StateSpec spec = rules.specAt(i);
                copy[i] = spec; // StateSpec is immutable, so sharing is safe
                h = 31 * h + spec.hashCode();
            }
            this.specs = copy;
            this.hash = h;
        }

        long retainedBytes() {
            return 16L + 4L * specs.length; // array header + references
        }

        @Override public int hashCode() { return hash; }

        @Override public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Key)) return false;
            Key that = (Key) other;
            if (hash != that.hash || specs.length != that.specs.length) return false;
            for (int i = 0; i < specs.length; i++) {
                if (!specs[i].equals(that.specs[i])) return false;
            }
            return true;
        }
    }
}
