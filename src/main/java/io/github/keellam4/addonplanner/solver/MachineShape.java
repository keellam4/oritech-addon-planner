package io.github.keellam4.addonplanner.solver;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * What the solver needs to know about a machine, all in the machine's own frame.
 *
 * @param occupied  cells taken by the machine: the controller (0,0,0) plus its core positions
 * @param baseSlots Oritech's getAddonSlots(), in Oritech's order
 */
public record MachineShape(Set<Cell> occupied, List<Cell> baseSlots) {

    public MachineShape {
        var occ = new LinkedHashSet<Cell>();
        occ.add(Cell.ORIGIN);
        occ.addAll(occupied);
        occupied = Set.copyOf(occ);
        baseSlots = List.copyOf(baseSlots);
    }

    public static MachineShape of(List<Cell> cores, List<Cell> baseSlots) {
        return new MachineShape(new LinkedHashSet<>(cores), new ArrayList<>(baseSlots));
    }

    public int floorY() {
        return occupied.stream().mapToInt(Cell::y).min().orElse(0);
    }
}
