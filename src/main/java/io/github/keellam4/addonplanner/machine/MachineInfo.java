package io.github.keellam4.addonplanner.machine;

import io.github.keellam4.addonplanner.solver.MachineShape;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;

/**
 * @param usesCores    true for multiblocks: their quality is the average of their Machine Cores (1-7)
 * @param fixedQuality quality for machines without cores
 * @param facing       the facing the default block state has, used to draw the machine in the planner
 */
public record MachineInfo(ResourceLocation id, Block block, MachineShape shape, boolean usesCores, float fixedQuality,
                          Direction facing) {
}
