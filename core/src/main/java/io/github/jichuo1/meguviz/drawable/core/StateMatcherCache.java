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
 *   <li>Access-order {@code LinkedHashMap.get} mutates the list, so lookups serialize on the
 *       monitor. This is acceptable at one lookup per instance; a per-frame caller would need a
 *       concurrent map plus a separate LRU list.</li>
 * </ul>
 */
public final class StateMatcherCache {
    /** Default cap: about 1024 small tables, or 8 worst-case K=16 tables. */
    public static final long DEFAULT_MAX_WEIGHT = 1L << 20;

    /** Distinct-ID counts above this need an explicit weight budget to be cached. */
    private static final int ALWAYS_CACHE_MAX_IDS = 8;

    /** Distinct IDs the matcher can ever compile into a table (mirrors {@code MAX_TABLE_IDS}). */
    private static final int MAX_TABLE_IDS = 16;

    /** Distinct IDs the scan can hold plus one sentinel for "over the table bound". */
    private static final int MAX_DISTINCT_SCAN = MAX_TABLE_IDS + 1;

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

        // Reject before building: a table that would be refused must not pay the preparation cost
        // (K=16/R=64 extrapolates to a few ms). The distinct-ID scan is O(rules × ids) and stops
        // at the largest compilable table, so this is cheap.
        int distinct = distinctIds(rules);
        if (distinct > MAX_TABLE_IDS) return null; // the matcher itself would fall back
        if (distinct > ALWAYS_CACHE_MAX_IDS && tableBytes(distinct) > maxWeight / 8) return null;

        StateMatcher.PreparedMatcher built = StateMatcher.prepare(rules);
        int ids = tableIdCount(built);
        if (ids < 0) return null; // delegating matcher: no benefit, and it retains the rules
        long cost = tableBytes(ids) + key.retainedBytes();
        // Admission is decided by the table payload alone: the key bytes are bookkeeping, and
        // including them would reject the largest table by a few dozen bytes.
        if (ids > ALWAYS_CACHE_MAX_IDS && tableBytes(ids) > maxWeight / 8) return null;
        if (cost > maxWeight) return null; // the entry could never stay resident

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

    /**
     * Counts distinct condition IDs without building a table, stopping once the count exceeds the
     * largest table the matcher can compile. The scan is O(rules × ids) and lets the admission
     * check reject an oversized table before paying the preparation cost.
     */
    private static int distinctIds(StateColorList rules) {
        int count = rules.size();
        int[] seen = new int[MAX_DISTINCT_SCAN];
        int distinct = 0;
        for (int i = 0; i < count; i++) {
            StateSpec spec = rules.specAt(i);
            for (int j = 0; j < spec.size(); j++) {
                int condition = spec.conditionAt(j);
                if (condition == 0) break;
                int id = condition > 0 ? condition : -condition;
                boolean known = false;
                for (int k = 0; k < distinct; k++) {
                    if (seen[k] == id) { known = true; break; }
                }
                if (known) continue;
                if (distinct == MAX_DISTINCT_SCAN - 1) return MAX_DISTINCT_SCAN; // over any table                seen[distinct++] = id;
            }
        }
        return distinct;
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

        /** Array header plus references; the retained StateSpec objects are not counted. */
        long retainedBytes() {
            return 16L + 4L * specs.length;
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
