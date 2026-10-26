package com.corrodinggames.rts.game.ai.custom.rwx_cli_tool;

import java.util.Iterator;
import java.util.LinkedHashSet;

/**
 * Insertion-ordered id set with a hard size cap; the oldest id is evicted first.
 *
 * <p>Used for the "already reported" ledger. The previous check
 * ({@code if (size() < cap) addAll(...)}) overshot the cap in one sample and then stopped
 * recording new ids entirely, which silently degraded {@code UNIT_CREATED} de-duplication for
 * the rest of the match. Adding one id at a time and evicting the oldest keeps the set strictly
 * bounded <em>and</em> always open to new ids.</p>
 */
final class RwxBoundedIdSet {
    private final int maxSize;
    private final LinkedHashSet<Long> ids = new LinkedHashSet<>();

    RwxBoundedIdSet(int maxSize) {
        this.maxSize = Math.max(1, maxSize);
    }

    boolean contains(long id) {
        return ids.contains(id);
    }

    /** Adds the id if absent and evicts the oldest entries until the cap holds again. */
    void add(long id) {
        if (!ids.add(id)) return;
        while (ids.size() > maxSize) {
            Iterator<Long> oldest = ids.iterator();
            oldest.next();
            oldest.remove();
        }
    }

    void clear() {
        ids.clear();
    }

    int size() {
        return ids.size();
    }
}
