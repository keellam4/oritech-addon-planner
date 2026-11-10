package io.github.keellam4.addonplanner.solver;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class LayoutSolverTest {

    // shapes copied from Oritech 1.2.13 (getCorePositions / getAddonSlots)
    static final MachineShape PULVERIZER = MachineShape.of(List.of(), cells(1, 0, 0));
    static final MachineShape ASSEMBLER = MachineShape.of(cells(0, 0, 1, 0, 1, 0, 0, 1, 1), cells(0, 0, -1, 0, 0, 2, 1, 0, 0));
    static final MachineShape LARGE_STORAGE = MachineShape.of(cells(0, 1, 0, 1, 0, 0, 1, 1, 0),
            cells(0, 0, -1, 0, 0, 1, 0, 1, -1, 0, 1, 1, 1, 0, -1, 1, 0, 1, 1, 1, -1, 1, 1, 1));

    static List<Cell> cells(int... v) {
        var l = new ArrayList<Cell>();
        for (int i = 0; i < v.length; i += 3) l.add(new Cell(v[i], v[i + 1], v[i + 2]));
        return l;
    }

    @Test
    void noExtendersGivesBaseSlots() {
        var r = AddonSearch.run(ASSEMBLER, Set.of(), 1, false);
        assertEquals(Set.copyOf(ASSEMBLER.baseSlots()), Set.copyOf(r.addonSlots()));
    }

    @Test
    void extenderLimitMatchesOritech() {
        // quality 1, non-layered: only one extender counts, the second is ignored
        var chain = Set.of(new Cell(1, 0, 0), new Cell(2, 0, 0));
        var r = AddonSearch.run(PULVERIZER, chain, 1, false);
        assertEquals(List.of(new Cell(1, 0, 0)), r.extenders());
        assertEquals(List.of(new Cell(2, 0, 0)), r.skippedExtenders());
        // quality 2: both count
        assertEquals(2, AddonSearch.run(PULVERIZER, chain, 2, false).extenders().size());
        // layered, quality 1: the second extender is found on the last layer, so its neighbours aren't searched
        var layered = AddonSearch.run(PULVERIZER, chain, 1, true);
        assertEquals(2, layered.extenders().size());
        assertFalse(layered.addonSlots().contains(new Cell(3, 0, 0)));
    }

    @Test
    void qualityIsTruncated() {
        // an average core quality of 2.9 behaves like 2
        assertEquals(LayoutSolver.solve(ASSEMBLER, 2, false).size(), LayoutSolver.solve(ASSEMBLER, 2.9f, false).size());
    }

    @Test
    void machineCellsAreNeverSlots() {
        for (var q = 1; q <= 7; q++) {
            var best = LayoutSolver.best(LayoutSolver.solve(LARGE_STORAGE, q, false));
            for (var s : best.result().addonSlots()) assertFalse(LARGE_STORAGE.occupied().contains(s));
            for (var e : best.result().extenders()) assertFalse(LARGE_STORAGE.occupied().contains(e));
        }
    }

    @Test
    void everySlotHasSomethingToMountOn() {
        for (var q = 1; q <= 7; q++) {
            var best = LayoutSolver.best(LayoutSolver.solve(ASSEMBLER, q, false));
            assertEquals(0, best.unsupportedSlots(), "quality " + q);
        }
    }

    @Test
    void knownBestCounts() {
        // each extender turns one slot into five new ones: +4 per extender when nothing is in the way
        var plans = LayoutSolver.solve(ASSEMBLER, 7, false);
        assertEquals(8, plans.size());
        for (int k = 0; k < plans.size(); k++) assertEquals(3 + 4 * k, plans.get(k).addonSlots(), "k=" + k);
    }

    @Test
    void beamMatchesExhaustiveSearchOnSmallCases() {
        for (var shape : List.of(PULVERIZER, ASSEMBLER)) {
            for (var q = 1; q <= 3; q++) {
                var plans = LayoutSolver.solve(shape, q, false);
                for (int k = 1; k < plans.size(); k++) {
                    assertEquals(exhaustive(shape, q, k), plans.get(k).addonSlots(), "q=" + q + " k=" + k);
                }
            }
        }
    }

    private static int exhaustive(MachineShape shape, float q, int k) {
        var candidates = new ArrayList<Cell>();
        var seen = new HashSet<Cell>();
        for (var b : shape.baseSlots()) {
            for (int dx = -k; dx <= k; dx++)
                for (int dy = -k; dy <= k; dy++)
                    for (int dz = -k; dz <= k; dz++) {
                        if (Math.abs(dx) + Math.abs(dy) + Math.abs(dz) > k - 1) continue;
                        var c = b.offset(dx, dy, dz);
                        if (!shape.occupied().contains(c) && seen.add(c)) candidates.add(c);
                    }
        }
        return pick(shape, q, candidates, 0, new HashSet<>(), k);
    }

    private static int pick(MachineShape shape, float q, List<Cell> cand, int i, Set<Cell> cur, int k) {
        if (cur.size() == k) {
            var r = AddonSearch.run(shape, cur, q, false);
            return r.extenders().size() == k ? r.addonSlots().size() : -1;
        }
        if (i == cand.size()) return -1;
        var best = pick(shape, q, cand, i + 1, cur, k);
        cur.add(cand.get(i));
        best = Math.max(best, pick(shape, q, cand, i + 1, cur, k));
        cur.remove(cand.get(i));
        return best;
    }
}
