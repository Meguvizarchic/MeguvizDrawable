import com.sun.management.ThreadMXBean;
import io.github.jichuo1.meguviz.drawable.model.DrawingRecipe;
import io.github.jichuo1.meguviz.drawable.model.FillSpec;
import io.github.jichuo1.meguviz.drawable.model.LayoutMetrics;
import io.github.jichuo1.meguviz.drawable.model.Length;
import io.github.jichuo1.meguviz.drawable.model.ShapeSpec;
import io.github.jichuo1.meguviz.drawable.model.StateColorList;
import io.github.jichuo1.meguviz.drawable.model.StateSpec;
import io.github.jichuo1.meguviz.drawable.model.StrokeSpec;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

/**
 * Host-JVM probe for the recipe model: hash cost of an already-hashed recipe, a HashMap lookup with
 * an equal but distinct key (the shape of a content-keyed cache), and allocation of recipe
 * construction. Prints CSV: metric,round,value. One JVM per run; run old and new class
 * directories interleaved. -Dprobe.hasOf=true also measures DrawingRecipe.of (new API only).
 */
public final class ModelProbe {
    private static final int ROUNDS = 7, WARMUP = 4, N = 1_000_000;
    static volatile int sink;
    static volatile Object objectSink;

    public static void main(String[] args) {
        DrawingRecipe a = recipe(), b = recipe();
        HashMap<DrawingRecipe, Integer> map = new HashMap<DrawingRecipe, Integer>();
        for (int i = 0; i < 64; i++) map.put(recipeWithColor(i), i);
        map.put(a, 7);
        b.hashCode();
        ShapeSpec shape = ShapeSpec.roundRect(Length.dp(12));
        FillSpec fill = FillSpec.solid(0xff3366ff);
        boolean hasOf = Boolean.getBoolean("probe.hasOf");
        ThreadMXBean mx = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        long tid = Thread.currentThread().getId();
        for (int round = -WARMUP; round < ROUNDS; round++) {
            long t0 = System.nanoTime();
            int acc = 0;
            for (int i = 0; i < N; i++) acc += a.hashCode();
            long t1 = System.nanoTime();
            for (int i = 0; i < N; i++) acc += map.get(b);
            long t2 = System.nanoTime();
            sink = acc;
            long m0 = mx.getThreadAllocatedBytes(tid);
            for (int i = 0; i < N; i++) objectSink = new DrawingRecipe(shape, fill, null, LayoutMetrics.unspecified());
            long m1 = mx.getThreadAllocatedBytes(tid);
            for (int i = 0; i < N; i++) objectSink = FillSpec.solid(i);
            long m2 = mx.getThreadAllocatedBytes(tid);
            long m3 = m2;
            if (hasOf) {
                for (int i = 0; i < N; i++) objectSink = DrawingRecipe.of(shape, fill);
                m3 = mx.getThreadAllocatedBytes(tid);
            }
            if (round < 0) continue;
            System.out.println("hash_ns," + round + "," + (t1 - t0) / (double) N);
            System.out.println("map_get_equal_key_ns," + round + "," + (t2 - t1) / (double) N);
            System.out.println("alloc_ctor_unspecified_B," + round + "," + (m1 - m0) / (double) N);
            System.out.println("alloc_fill_solid_B," + round + "," + (m2 - m1) / (double) N);
            if (hasOf) System.out.println("alloc_of_B," + round + "," + (m3 - m2) / (double) N);
        }
    }

    private static DrawingRecipe recipe() { return recipeWithColor(-1); }

    /** STATEFUL fill with 8 rules, a stroke and rounded corners: a realistic button recipe. */
    private static DrawingRecipe recipeWithColor(int salt) {
        List<StateSpec> specs = new ArrayList<StateSpec>();
        int[] colors = new int[8];
        int[] ids = {0x0101009e, 0x0101009c, 0x010100a7, 0x010100a1, 0x010100a0, 0x010102fe, 0x01010367, 0x0101009d};
        for (int i = 0; i < 8; i++) {
            specs.add(i == 7 ? new StateSpec() : new StateSpec(ids[i], -ids[(i + 1) % 8]));
            colors[i] = 0xff000000 | (i * 0x111111) ^ salt;
        }
        Length[] radii = new Length[8];
        Arrays.fill(radii, Length.dp(12));
        return new DrawingRecipe(ShapeSpec.roundRect(radii),
                FillSpec.statefulSolid(new StateColorList(specs, colors), 0xff000000 ^ salt),
                new StrokeSpec(Length.dp(1), 0x33000000), LayoutMetrics.unspecified());
    }
}
