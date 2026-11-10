package io.github.keellam4.addonplanner.machine;

import io.github.keellam4.addonplanner.solver.LayoutSolver;
import net.minecraft.Util;
import rearth.oritech.init.OritechConfig;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/** Solves layouts off the render thread and remembers them for the session. */
public final class PlanCache {

    private record Key(MachineInfo machine, int quality, boolean layered) {
    }

    private static final Map<Key, CompletableFuture<List<LayoutSolver.Plan>>> CACHE = new ConcurrentHashMap<>();

    private PlanCache() {
    }

    public static CompletableFuture<List<LayoutSolver.Plan>> get(MachineInfo machine, float quality, boolean layered) {
        var key = new Key(machine, (int) quality, layered);
        return CACHE.computeIfAbsent(key, k -> CompletableFuture.supplyAsync(
                () -> LayoutSolver.solve(machine.shape(), k.quality(), k.layered()), Util.backgroundExecutor()));
    }

    /** Oritech's own "layeredExtenders" setting as this client sees it (servers may differ). */
    public static boolean layeredDefault() {
        try {
            return OritechConfig.layeredExtenders.get();
        } catch (Throwable t) {
            return false; // config not loaded yet
        }
    }
}
