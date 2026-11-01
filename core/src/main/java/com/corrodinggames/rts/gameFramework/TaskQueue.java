package com.corrodinggames.rts.gameFramework;

import com.corrodinggames.rts.gameFramework.utility.FastArrayList;

/* JADX INFO: renamed from: com.corrodinggames.rts.gameFramework.q */
/* JADX INFO: loaded from: game-lib.jar:com/corrodinggames/rts/gameFramework/q.class */
public class TaskQueue {

    /* JADX INFO: renamed from: a */
    FastArrayList tasks = new FastArrayList();

    public void a(Runnable runnable) {
        // Producers are the CLI client threads, the micro clock and the observation sampler, while
        // the game thread drains this queue every frame.
        synchronized (this) {
            this.tasks.add(runnable);
        }
    }

    public void a() {
        Object[] batch;
        synchronized (this) {
            if (this.tasks.size == 0) {
                return;
            }
            batch = this.tasks.toArray();
        }
        for (Object task : batch) {
            ((Runnable) task).run();
        }
    }

    public void b() {
        Object[] batch;
        synchronized (this) {
            if (this.tasks.size == 0) {
                return;
            }
            batch = this.tasks.toArray();
            this.tasks.clear();
        }
        // Tasks queued while running wait for the next frame; iterating the live list would throw
        // ConcurrentModificationException when a producer adds during the drain.
        for (Object task : batch) {
            ((Runnable) task).run();
        }
    }
}
