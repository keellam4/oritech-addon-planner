package io.github.keellam4.addonplanner.solver;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Finds extender placements that give a machine the most addon slots, for each number of extenders.
 * Beam search: grow layouts one extender at a time, always placing the new extender on a reachable slot
 * whose neighbours will still be searched, and keep the best layouts at each size.
 */
public final class LayoutSolver {

    /** Layered mode has no extender limit; stop here so plans stay buildable. */
    public static final int LAYERED_EXTENDER_CAP = 24;
    private static final int BEAM_WIDTH = 160;

    private LayoutSolver() {
    }

    public static int maxExtenders(float quality, boolean layered) {
        var q = Math.max(0, (int) quality);
        if (q == 0) return 0;
        return layered ? LAYERED_EXTENDER_CAP : q;
    }

    /**
     * @return one plan per extender count, index 0 = no extenders. Stops early once more extenders stop helping.
     */
    public static List<Plan> solve(MachineShape shape, float quality, boolean layered) {
        var maxIterations = (int) quality + 1;
        var maxK = maxExtenders(quality, layered);

        var plans = new ArrayList<Plan>();
        var empty = evaluate(shape, Set.of(), quality, layered);
        plans.add(empty);
        if (shape.baseSlots().isEmpty()) return plans;

        List<Plan> beam = List.of(empty);
        var stale = 0;
        for (int k = 1; k <= maxK; k++) {
            var next = new LinkedHashMap<String, Plan>();
            for (var plan : beam) {
                for (var slot : plan.result().addonSlots()) {
                    // an extender on the last searched layer would connect nothing new
                    if (plan.result().layer().get(slot) >= maxIterations - 1) continue;
                    var ext = new TreeSet<>(plan.result().extenders());
                    ext.add(slot);
                    var key = ext.toString();
                    if (next.containsKey(key)) continue;
                    var candidate = evaluate(shape, ext, quality, layered);
                    if (candidate.result().extenders().size() != k) continue;
                    next.put(key, candidate);
                }
            }
            if (next.isEmpty()) break;
            var sorted = new ArrayList<>(next.values());
            sorted.sort(BETTER_FIRST);
            beam = sorted.subList(0, Math.min(BEAM_WIDTH, sorted.size()));
            var best = beam.getFirst();
            plans.add(best);

            // layered mode can keep going for a long time; stop once extra extenders stop paying off
            if (best.addonSlots() <= plans.get(k - 1).addonSlots()) {
                if (++stale >= 3 && layered) break;
            } else {
                stale = 0;
            }
        }
        return plans;
    }

    /** The plan with the most addon slots, using as few extenders as possible. */
    public static Plan best(List<Plan> plans) {
        Plan best = plans.getFirst();
        for (var p : plans) {
            if (p.addonSlots() > best.addonSlots()) best = p;
        }
        return best;
    }

    static Plan evaluate(MachineShape shape, Set<Cell> extenders, float quality, boolean layered) {
        var result = AddonSearch.run(shape, extenders, quality, layered);
        var floor = shape.floorY();
        var extendersBelow = 0;
        var slotsBelow = 0;
        var unsupported = 0;
        int minX = 0, minY = 0, minZ = 0, maxX = 0, maxY = 0, maxZ = 0;
        var all = new HashSet<Cell>(shape.occupied());
        all.addAll(result.extenders());
        all.addAll(result.addonSlots());
        for (var c : all) {
            minX = Math.min(minX, c.x()); maxX = Math.max(maxX, c.x());
            minY = Math.min(minY, c.y()); maxY = Math.max(maxY, c.y());
            minZ = Math.min(minZ, c.z()); maxZ = Math.max(maxZ, c.z());
        }
        for (var c : result.extenders()) if (c.y() < floor) extendersBelow++;
        for (var c : result.addonSlots()) {
            if (c.y() < floor) slotsBelow++;
            if (result.support().get(c) == null) unsupported++;
        }
        var volume = (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
        return new Plan(result, extendersBelow, slotsBelow, unsupported, volume);
    }

    // most slots, then fewest blocks under the machine's lowest block (less digging), then fewest floating addons, then most compact
    static final Comparator<Plan> BETTER_FIRST = Comparator
            .comparingInt(Plan::addonSlots).reversed()
            .thenComparingInt(Plan::extendersBelowMachine)
            .thenComparingInt(Plan::slotsBelowMachine)
            .thenComparingInt(Plan::unsupportedSlots)
            .thenComparingInt(Plan::boundingVolume)
            .thenComparing(p -> p.result().extenders().toString());

    /**
     * @param extendersBelowMachine extenders under the machine's lowest block (you'd have to dig)
     * @param slotsBelowMachine     addon slots under the machine's lowest block (usable, but you'd have to dig)
     * @param unsupportedSlots      slots with nothing to mount an addon on
     */
    public record Plan(AddonSearch.Result result, int extendersBelowMachine, int slotsBelowMachine, int unsupportedSlots,
                       int boundingVolume) {
        public int addonSlots() {
            return result.addonSlots().size();
        }

        public int extenderCount() {
            return result.extenders().size();
        }
    }
}
