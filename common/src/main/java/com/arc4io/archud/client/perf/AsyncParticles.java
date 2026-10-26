package com.arc4io.archud.client.perf;

import com.arc4io.archud.ArcHud;
import com.arc4io.archud.mixin.ParticleEngineInvoker;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.client.particle.TextureSheetParticle;

import java.util.Collection;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Многопоточное обновление частиц (как AsyncParticles). Когда частиц много, их tick() раздаётся рабочим потокам;
 * главный поток ждёт окончания, поэтому мир в это время никто не меняет — частицы только читают блоки.
 * <ul>
 *   <li>параллельно — только простые ванильные частицы (движение и столкновения); частицы модов, фейерверки,
 *       «подбор предмета», капли и лава (порождают новые частицы) — на главном потоке, как обычно;</li>
 *   <li>новые частицы, созданные из рабочего потока, складываются в очередь и добавляются после;</li>
 *   <li>если тип частицы бросил исключение в рабочем потоке — он навсегда переводится на главный поток,
 *       а эта частица повторно обновляется там (игра не падает).</li>
 * </ul>
 * С OptiFine выключено (он меняет движок частиц).
 */
public final class AsyncParticles {
    private static final int MIN_PARTICLES = 400;
    private static final int THREADS = Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() - 2));
    private static final AtomicInteger IDS = new AtomicInteger();
    private static final ExecutorService POOL = Executors.newFixedThreadPool(THREADS, r -> {
        Thread t = new Thread(r, "Arc HUD particles #" + IDS.incrementAndGet());
        t.setDaemon(true);
        return t;
    });
    private static final Map<Class<?>, Boolean> SAFE = new ConcurrentHashMap<>();
    private static final Set<Class<?>> FAILED = ConcurrentHashMap.newKeySet();
    private static final ConcurrentLinkedQueue<Particle> DEFERRED = new ConcurrentLinkedQueue<>();
    private static volatile boolean parallel;
    private static volatile Thread main;

    private AsyncParticles() {}

    private static boolean safe(Class<?> c) {
        return SAFE.computeIfAbsent(c, k -> {
            String n = k.getName();
            if (!n.startsWith("net.minecraft.client.particle.")) return false; // частицы модов — как обычно
            if (!TextureSheetParticle.class.isAssignableFrom(k)) return false;
            return !(n.contains("Firework") || n.contains("ItemPickup") || n.contains("Tracking") || n.contains("DripParticle")
                    || n.contains("LavaParticle") || n.contains("HugeExplosion") || n.contains("Suspended"));
        }) && !FAILED.contains(c);
    }

    /** Из ParticleEngine.add: новая частица из рабочего потока — в очередь. */
    public static boolean deferAdd(Particle p) {
        if (parallel && Thread.currentThread() != main) {
            DEFERRED.add(p);
            return true;
        }
        return false;
    }

    /** Обновить список частиц; false — пусть обновит ванила (мало частиц / выключено). */
    public static boolean tickList(ParticleEngine engine, Collection<Particle> list) {
        if (list.size() < MIN_PARTICLES || !Perf.on("p_async") || com.arc4io.archud.compat.OptiFineCompat.present()) return false;
        Particle[] arr = list.toArray(new Particle[0]);
        int n = arr.length;
        boolean[] onWorker = new boolean[n];
        boolean[] redo = new boolean[n];
        for (int i = 0; i < n; i++) onWorker[i] = safe(arr[i].getClass());
        main = Thread.currentThread();
        parallel = true;
        try {
            int chunk = (n + THREADS - 1) / THREADS;
            Future<?>[] fs = new Future<?>[THREADS];
            for (int t = 0; t < THREADS; t++) {
                int from = t * chunk, to = Math.min(n, from + chunk);
                fs[t] = POOL.submit(() -> {
                    for (int i = from; i < to; i++) {
                        if (!onWorker[i]) continue;
                        Particle p = arr[i];
                        try {
                            p.tick();
                        } catch (Throwable e) {
                            if (FAILED.add(p.getClass())) ArcHud.LOGGER.info("Arc HUD: particle {} moved to the main thread ({})", p.getClass().getName(), e.toString());
                            redo[i] = true;
                        }
                    }
                });
            }
            for (Future<?> f : fs) {
                try {
                    f.get();
                } catch (Exception e) {
                    // не должно случиться: исключения частиц ловятся внутри
                }
            }
        } finally {
            parallel = false;
        }
        ParticleEngineInvoker inv = (ParticleEngineInvoker) engine;
        for (int i = 0; i < n; i++) if (!onWorker[i] || redo[i]) inv.archud$tickParticle(arr[i]);
        Particle d;
        while ((d = DEFERRED.poll()) != null) engine.add(d);
        Iterator<Particle> it = list.iterator();
        while (it.hasNext()) {
            Particle p = it.next();
            if (!p.isAlive()) {
                p.getParticleGroup().ifPresent(g -> inv.archud$updateCount(g, -1));
                it.remove();
            }
        }
        return true;
    }
}
