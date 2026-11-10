package io.github.keellam4.addonplanner.solver;

import java.util.List;

/** A block position in the machine's own (unrotated) frame. The controller is at 0,0,0. */
public record Cell(int x, int y, int z) implements Comparable<Cell> {

    public static final Cell ORIGIN = new Cell(0, 0, 0);

    public Cell offset(int dx, int dy, int dz) {
        return new Cell(x + dx, y + dy, z + dz);
    }

    /** Same order as Oritech's MachineAddonController.getNeighbors. */
    public List<Cell> neighbours() {
        return List.of(offset(-1, 0, 0), offset(1, 0, 0), offset(0, 0, -1), offset(0, 0, 1), offset(0, -1, 0), offset(0, 1, 0));
    }

    public boolean isNeighbour(Cell other) {
        return Math.abs(x - other.x) + Math.abs(y - other.y) + Math.abs(z - other.z) == 1;
    }

    @Override
    public int compareTo(Cell o) {
        if (y != o.y) return Integer.compare(y, o.y);
        if (x != o.x) return Integer.compare(x, o.x);
        return Integer.compare(z, o.z);
    }

    @Override
    public String toString() {
        return "(" + x + "," + y + "," + z + ")";
    }
}
