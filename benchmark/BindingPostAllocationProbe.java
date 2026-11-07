package io.github.jichuo1.meguviz.drawable.benchmark;

import com.sun.management.ThreadMXBean;
import io.github.jichuo1.meguviz.android.runtime.core.BoundedMailbox;
import io.github.jichuo1.meguviz.android.runtime.core.FrameDriver;
import io.github.jichuo1.meguviz.android.runtime.core.FrameScheduler;
import io.github.jichuo1.meguviz.android.runtime.core.LifecycleScope;
import io.github.jichuo1.meguviz.android.runtime.core.OwnerDispatcher;
import io.github.jichuo1.meguviz.android.runtime.resources.ResourceSnapshot;
import io.github.jichuo1.meguviz.android.runtime.resources.ResourceState;
import io.github.jichuo1.meguviz.drawable.binding.BindingScope;
import io.github.jichuo1.meguviz.drawable.binding.SlotBinding;
import io.github.jichuo1.meguviz.drawable.meguviz.android.MeguvizBinding;
import java.lang.management.ManagementFactory;
import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Host-only allocation probe for the MeguvizAndroid bridge's per-request path, using the real
 * MeguvizAndroid runtime artifact. Measures {@code project}, {@code begin} and the full
 * {@code begin + post + drain + frame} cycle with {@code ThreadMXBean.getThreadAllocatedBytes}.
 *
 * <p>This is not an Android/ART benchmark and does not claim frame-time or device results. It
 * requires the {@code meguviz-android-runtime} artifact (1.2.0 or compatible) on the compile and
 * run classpath; {@code MeguvizBinding} itself imports no Android framework class, so the probe
 * runs on a plain host JVM. Raw output is CSV on stdout.
 */
public final class BindingPostAllocationProbe {
    private static final class Dispatcher implements OwnerDispatcher {
        private final Thread owner = Thread.currentThread();
        private final ArrayDeque<Runnable> queue = new ArrayDeque<Runnable>();
        @Override public boolean isOwnerThread() { return Thread.currentThread() == owner; }
        @Override public synchronized boolean post(Runnable callback) { queue.add(callback); return true; }
        @Override public synchronized void remove(Runnable callback) { queue.remove(callback); }
        void drain() {
            while (true) {
                Runnable next;
                synchronized (this) { next = queue.poll(); }
                if (next == null) return;
                next.run();
            }
        }
    }

    private static final class Driver implements FrameDriver {
        private Callback pending;
        @Override public boolean request(Callback callback) { pending = callback; return true; }
        @Override public void remove(Callback callback) { if (pending == callback) pending = null; }
        void frame() { Callback callback = pending; pending = null; if (callback != null) callback.onFrame(16_000_000L); }
    }

    private static final SlotBinding.Releaser<Object> RELEASE = new SlotBinding.Releaser<Object>() {
        @Override public void release(Object value) { }
    };

    private static final BindingScope.Installer<Object> ACCEPT = new BindingScope.Installer<Object>() {
        @Override public boolean install(Object result) { return true; }
    };

    private static final MeguvizBinding.Listener LISTENER = new MeguvizBinding.Listener() {
        @Override public void onResult(MeguvizBinding.UpdateResult result) { }
        @Override public void onFailure(Throwable failure) { throw new AssertionError(failure); }
    };

    private static final int OPS = 200000;

    public static void main(String[] args) {
        Dispatcher dispatcher = new Dispatcher();
        Driver driver = new Driver();
        FrameScheduler scheduler = new FrameScheduler(dispatcher, driver, error -> { throw new AssertionError(error); }, 8);
        LifecycleScope lifecycle = new LifecycleScope(dispatcher, error -> { throw new AssertionError(error); });
        ResourceState resources = new ResourceState(dispatcher, "test", snapshot(320), 4);
        BoundedMailbox mailbox = new BoundedMailbox(lifecycle, scheduler, error -> { throw new AssertionError(error); }, 64, 64, 64);
        MeguvizBinding binding = MeguvizBinding.create(lifecycle, resources, mailbox, () -> 1L, 7);
        lifecycle.setAttached(true);
        lifecycle.setStarted(true);
        binding.sync();

        ThreadMXBean bean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        long tid = Thread.currentThread().getId();

        // Warm up every measured path.
        for (int i = 0; i < 20000; i++) {
            binding.project();
            binding.begin("a");
            binding.post(binding.begin("a"), new Object(), ACCEPT, RELEASE, LISTENER);
            dispatcher.drain();
            driver.frame();
        }

        System.out.println("meta,java," + System.getProperty("java.version")
                + ",vm," + System.getProperty("java.vm.name")
                + ",arch," + System.getProperty("os.arch")
                + ",cores," + Runtime.getRuntime().availableProcessors()
                + ",rawAllocation," + Boolean.getBoolean("probe.rawAllocation"));
        System.out.println("columns,scenario,ops,alloc_bytes_per_call,ns_per_call,sink");

        final Object result = new Object();
        measure("project", bean, tid, () -> binding.project());
        measure("begin", bean, tid, () -> binding.begin("a"));
        measure("begin+post+drain+frame", bean, tid, () -> {
            MeguvizBinding.Request request = binding.begin("a");
            binding.post(request, result, ACCEPT, RELEASE, LISTENER);
            dispatcher.drain();
            driver.frame();
        });

        lifecycle.close();
        resources.close();
        scheduler.close();
    }

    private interface Scenario {
        void run();
    }

    private static void measure(String name, ThreadMXBean bean, long tid, Scenario scenario) {
        long before = bean.getThreadAllocatedBytes(tid);
        long start = System.nanoTime();
        for (int i = 0; i < OPS; i++) {
            scenario.run();
        }
        long elapsed = System.nanoTime() - start;
        long after = bean.getThreadAllocatedBytes(tid);
        System.out.println("sample," + name + "," + OPS + ","
                + String.format("%.3f", (after - before) / (double) OPS) + ","
                + String.format("%.1f", elapsed / (double) OPS) + ",0");
    }

    private static ResourceSnapshot snapshot(int dpi) {
        return new ResourceSnapshot(dpi, 1f, "en", 0, 0, 360, 640, 0, "base");
    }
}
