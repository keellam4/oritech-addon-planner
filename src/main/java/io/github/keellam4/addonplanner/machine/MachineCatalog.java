package io.github.keellam4.addonplanner.machine;

import io.github.keellam4.addonplanner.AddonPlanner;
import io.github.keellam4.addonplanner.solver.Cell;
import io.github.keellam4.addonplanner.solver.MachineShape;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import rearth.oritech.util.MachineAddonController;
import rearth.oritech.util.MultiblockMachineController;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Every block (from Oritech or an addon built on it) whose block entity takes Oritech machine addons.
 * Read once from the block registry, by creating a throwaway block entity and asking it for its slots.
 */
public final class MachineCatalog {

    private static final boolean VERBOSE = Boolean.getBoolean(AddonPlanner.MOD_ID + ".smokeTest");
    private static List<MachineInfo> machines;
    private static Map<Block, MachineInfo> byBlock;

    private MachineCatalog() {
    }

    public static synchronized List<MachineInfo> get() {
        if (machines == null) load();
        return machines;
    }

    public static MachineInfo of(Block block) {
        get();
        return byBlock.get(block);
    }

    private static void load() {
        var found = new ArrayList<MachineInfo>();
        for (var block : BuiltInRegistries.BLOCK) {
            if (!(block instanceof EntityBlock entityBlock)) continue;
            var id = BuiltInRegistries.BLOCK.getKey(block);
            if (!isOritechBased(id, block)) continue;
            if (VERBOSE) AddonPlanner.LOG.info("[catalog] reading {}", id);
            try {
                var info = read(id, block, entityBlock.newBlockEntity(BlockPos.ZERO, block.defaultBlockState()));
                if (info != null) found.add(info);
            } catch (Throwable t) {
                AddonPlanner.LOG.debug("Skipping {}: {}", id, t.toString());
            }
        }
        found.sort(Comparator.comparing((MachineInfo m) -> m.block().getName().getString()));
        machines = List.copyOf(found);
        byBlock = new HashMap<>();
        for (var m : machines) byBlock.put(m.block(), m);
        AddonPlanner.LOG.info("Found {} machines that take addons", machines.size());
    }

    // only look at blocks Oritech or Oritech addons made, so other mods' block entities are never created here
    private static boolean isOritechBased(ResourceLocation id, Block block) {
        if (id.getNamespace().startsWith("oritech")) return true;
        for (Class<?> c = block.getClass(); c != null; c = c.getSuperclass()) {
            if (c.getName().startsWith("rearth.oritech.")) return true;
        }
        return false;
    }

    private static MachineInfo read(ResourceLocation id, Block block, BlockEntity entity) {
        if (!(entity instanceof MachineAddonController controller)) return null;
        var slots = toCells(controller.getAddonSlots());
        if (slots.isEmpty()) return null;

        List<Cell> cores = List.of();
        var usesCores = false;
        if (entity instanceof MultiblockMachineController multiblock) {
            cores = toCells(multiblock.getCorePositions());
            usesCores = !cores.isEmpty();
        }
        var quality = usesCores ? 1f : controller.getCoreQuality();
        return new MachineInfo(id, block, MachineShape.of(cores, slots), usesCores, quality, previewFacing(controller));
    }

    private static Direction previewFacing(MachineAddonController controller) {
        try {
            return controller.getFacingForAddon();
        } catch (Throwable t) {
            return Direction.NORTH; // needs a level for some machines
        }
    }

    private static List<Cell> toCells(List<Vec3i> list) {
        var cells = new ArrayList<Cell>(list.size());
        for (var v : list) cells.add(new Cell(v.getX(), v.getY(), v.getZ()));
        return cells;
    }
}
