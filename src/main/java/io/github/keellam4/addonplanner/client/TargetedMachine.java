package io.github.keellam4.addonplanner.client;

import io.github.keellam4.addonplanner.machine.MachineCatalog;
import io.github.keellam4.addonplanner.machine.MachineInfo;
import io.github.keellam4.addonplanner.solver.Cell;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.jetbrains.annotations.Nullable;
import rearth.oritech.block.base.block.MultiblockMachine;
import rearth.oritech.block.blocks.addons.MachineAddonBlock;
import rearth.oritech.block.blocks.processing.MachineCoreBlock;
import rearth.oritech.block.entity.MachineCoreEntity;
import rearth.oritech.block.entity.addons.AddonBlockEntity;
import rearth.oritech.util.Geometry;
import rearth.oritech.util.MachineAddonController;
import rearth.oritech.util.MultiblockMachineController;

/**
 * A machine placed in the world, with what's needed to map the planner's layout onto it.
 *
 * @param addonFacing facing Oritech uses for addon slots
 * @param coreFacing  facing Oritech uses for core positions (same as addonFacing for every Oritech machine so far)
 * @param quality     the machine's core quality right now (or what its placed cores would give)
 */
public record TargetedMachine(MachineInfo machine, BlockPos pos, Direction addonFacing, Direction coreFacing, float quality) {

    @Nullable
    public static TargetedMachine find(Minecraft mc) {
        if (mc.level == null || !(mc.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) return null;
        return at(mc.level, hit.getBlockPos());
    }

    @Nullable
    public static TargetedMachine at(Level level, BlockPos pos) {
        pos = controllerOf(level, pos);
        var info = MachineCatalog.of(level.getBlockState(pos).getBlock());
        if (info == null || !(level.getBlockEntity(pos) instanceof MachineAddonController controller)) return null;

        Direction addonFacing;
        try {
            addonFacing = controller.getFacingForAddon();
        } catch (Throwable t) {
            return null;
        }
        var coreFacing = addonFacing;
        var quality = info.fixedQuality();
        if (controller instanceof MultiblockMachineController multiblock && info.usesCores()) {
            try {
                coreFacing = multiblock.getFacingForMultiblock();
            } catch (Throwable ignored) {
            }
            var state = level.getBlockState(pos);
            var assembled = state.hasProperty(MultiblockMachine.ASSEMBLED) && state.getValue(MultiblockMachine.ASSEMBLED);
            quality = assembled ? multiblock.getCoreQuality() : placedCoreQuality(level, pos, coreFacing, info);
        }
        return new TargetedMachine(info, pos, addonFacing, coreFacing, quality);
    }

    // looking at a core or a connected addon counts as looking at its machine
    private static BlockPos controllerOf(Level level, BlockPos pos) {
        var state = level.getBlockState(pos);
        var entity = level.getBlockEntity(pos);
        if (entity instanceof MachineCoreEntity core && state.hasProperty(MachineCoreBlock.USED) && state.getValue(MachineCoreBlock.USED)) {
            return core.getControllerPos();
        }
        if (entity instanceof AddonBlockEntity addon && state.hasProperty(MachineAddonBlock.ADDON_USED) && state.getValue(MachineAddonBlock.ADDON_USED)) {
            return addon.getControllerPos();
        }
        return pos;
    }

    // before a multiblock is assembled, guess its quality from the cores already placed (unplaced count as tier 1)
    private static float placedCoreQuality(Level level, BlockPos pos, Direction facing, MachineInfo info) {
        var sum = 0f;
        var count = 0;
        for (var cell : info.shape().occupied()) {
            if (cell.equals(Cell.ORIGIN)) continue;
            var state = level.getBlockState(toWorld(cell, facing, pos));
            sum += state.getBlock() instanceof MachineCoreBlock core ? core.getCoreQuality() : 1f;
            count++;
        }
        return count == 0 ? 1f : sum / count;
    }

    public BlockPos slotToWorld(Cell cell) {
        return toWorld(cell, addonFacing, pos);
    }

    public BlockPos coreToWorld(Cell cell) {
        return toWorld(cell, coreFacing, pos);
    }

    public static BlockPos toWorld(Cell cell, Direction facing, BlockPos origin) {
        return new BlockPos(Geometry.offsetToWorldPosition(facing, new Vec3i(cell.x(), cell.y(), cell.z()), origin));
    }
}
