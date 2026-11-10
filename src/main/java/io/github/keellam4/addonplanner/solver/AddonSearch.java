package io.github.keellam4.addonplanner.solver;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A port of Oritech's MachineAddonController.getAllAddons (v1.2.13), run on a planned layout instead of a world.
 * Every free cell the search reaches is an addon slot; cells listed as extenders are treated as extender blocks.
 */
public final class AddonSearch {

    private AddonSearch() {
    }

    /**
     * @param extenders  where extender blocks are planned
     * @param quality    the machine's core quality (Oritech truncates it to an int)
     * @param layered    Oritech's "layeredExtenders" config value
     */
    public static Result run(MachineShape shape, Set<Cell> extenders, float quality, boolean layered) {
        var maxIterations = (int) quality + 1;
        var searched = new HashSet<Cell>();
        var queued = new ArrayList<Cell>(shape.baseSlots());
        var toAdd = new LinkedHashSet<Cell>();
        var toRemove = new HashSet<Cell>();

        var usedExtenders = new ArrayList<Cell>();
        var slots = new ArrayList<Cell>();
        var layer = new HashMap<Cell, Integer>();
        var support = new HashMap<Cell, Cell>();
        var skippedExtenders = new ArrayList<Cell>();
        var foundExtenders = 0;

        for (var slot : shape.baseSlots()) {
            support.putIfAbsent(slot, supportOnMachine(shape, slot));
        }

        for (int i = 0; i < maxIterations; i++) {
            if (queued.isEmpty()) break;
            for (var candidate : queued) {
                if (!searched.add(candidate)) continue;
                toRemove.add(candidate);

                if (shape.occupied().contains(candidate)) continue; // part of the machine itself
                if (!extenders.contains(candidate)) {
                    slots.add(candidate);
                    layer.put(candidate, i);
                    continue;
                }

                if (!layered) {
                    if (foundExtenders < maxIterations - 1) {
                        foundExtenders++;
                    } else {
                        skippedExtenders.add(candidate);
                        continue;
                    }
                }

                usedExtenders.add(candidate);
                layer.put(candidate, i);
                for (var n : candidate.neighbours()) {
                    if (!searched.contains(n)) {
                        toAdd.add(n);
                        support.putIfAbsent(n, candidate);
                    }
                }
            }
            queued.addAll(toAdd);
            queued.removeAll(toRemove);
            toAdd.clear();
            toRemove.clear();
        }

        var slotSupport = new HashMap<Cell, Cell>();
        for (var s : slots) {
            var sup = support.get(s);
            // a neighbour cell queued by an extender that was later used as a slot keeps that extender as support
            if (sup != null && !(usedExtenders.contains(sup) || shape.occupied().contains(sup))) sup = null;
            slotSupport.put(s, sup);
        }
        return new Result(List.copyOf(usedExtenders), List.copyOf(slots), Map.copyOf(layer), slotSupport, List.copyOf(skippedExtenders));
    }

    private static Cell supportOnMachine(MachineShape shape, Cell slot) {
        for (var n : slot.neighbours()) {
            if (shape.occupied().contains(n)) return n;
        }
        return null;
    }

    /**
     * @param extenders        extenders the machine accepts
     * @param addonSlots       free cells where an addon would connect
     * @param layer            search layer each extender/slot was found in (0 = base slot)
     * @param support          for each slot, the block an addon would be mounted on (null = needs another block)
     * @param skippedExtenders planned extenders past the extender limit (they would be ignored in game)
     */
    public record Result(List<Cell> extenders, List<Cell> addonSlots, Map<Cell, Integer> layer, Map<Cell, Cell> support,
                         List<Cell> skippedExtenders) {
    }
}
