package io.github.keellam4.addonplanner.client;

import io.github.keellam4.addonplanner.AddonPlanner;
import io.github.keellam4.addonplanner.solver.Cell;
import io.github.keellam4.addonplanner.solver.LayoutSolver;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.debug.DebugRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.jetbrains.annotations.Nullable;
import rearth.oritech.block.base.block.MultiblockMachine;
import rearth.oritech.block.blocks.addons.MachineAddonBlock;
import rearth.oritech.block.blocks.processing.MachineCoreBlock;

import java.util.ArrayList;
import java.util.List;

/**
 * Projects a plan onto a machine in the world. Blocks that are already right disappear;
 * missing ones show as coloured ghosts and blocks in the way get a red outline.
 */
public final class Hologram {

    private enum Kind { CORE, EXTENDER, SLOT }

    private enum Status { DONE, TODO, LOW_TIER, BLOCKED }

    private record Entry(BlockPos pos, Kind kind, Status status) {
    }

    private static final double MAX_DISTANCE = 96;

    private static TargetedMachine target;
    private static LayoutSolver.Plan plan;
    private static int coreTier; // planned Machine Core tier for multiblocks, 0 = don't check
    private static List<Entry> entries = List.of();
    private static int ticks;

    private Hologram() {
    }

    public static boolean isActive() {
        return target != null;
    }

    public static boolean isShowing(TargetedMachine machine) {
        return target != null && machine != null && target.pos().equals(machine.pos());
    }

    public static void show(TargetedMachine machine, LayoutSolver.Plan newPlan) {
        show(machine, newPlan, machine.machine().usesCores() ? Math.max(1, (int) machine.quality()) : 0);
    }

    public static void show(TargetedMachine machine, LayoutSolver.Plan newPlan, int plannedCoreTier) {
        target = machine;
        plan = newPlan;
        coreTier = plannedCoreTier;
        ticks = 0;
        var mc = Minecraft.getInstance();
        if (mc.level != null) entries = scan(mc.level);
    }

    public static void clear() {
        target = null;
        plan = null;
        entries = List.of();
    }

    public static void tick(Minecraft mc) {
        if (target == null) return;
        var level = mc.level;
        if (level == null || mc.player == null) {
            clear();
            return;
        }
        if (level.getBlockState(target.pos()).getBlock() != target.machine().block()) {
            mc.player.displayClientMessage(Component.translatable("message." + AddonPlanner.MOD_ID + ".machine_gone"), true);
            clear();
            return;
        }
        if (mc.player.position().distanceTo(target.pos().getCenter()) > MAX_DISTANCE) {
            clear();
            return;
        }
        if (ticks++ % 5 == 0) entries = scan(level);
        if (ticks % 20 == 1) mc.player.displayClientMessage(summary(), true);
    }

    private static List<Entry> scan(Level level) {
        var list = new ArrayList<Entry>();
        var machinePos = target.pos();
        var machineState = level.getBlockState(machinePos);
        var assembled = machineState.hasProperty(MultiblockMachine.ASSEMBLED) && machineState.getValue(MultiblockMachine.ASSEMBLED);

        if (target.machine().usesCores() && !assembled) {
            for (var cell : target.machine().shape().occupied()) {
                if (cell.equals(Cell.ORIGIN)) continue;
                var pos = target.coreToWorld(cell);
                list.add(new Entry(pos, Kind.CORE, coreStatus(level.getBlockState(pos))));
            }
        }
        for (var cell : plan.result().extenders()) {
            var pos = target.slotToWorld(cell);
            var state = level.getBlockState(pos);
            Status status;
            if (state.getBlock() instanceof MachineAddonBlock addon && addon.getAddonSettings().extender()) status = Status.DONE;
            else status = state.canBeReplaced() ? Status.TODO : Status.BLOCKED;
            list.add(new Entry(pos, Kind.EXTENDER, status));
        }
        for (var cell : plan.result().addonSlots()) {
            var pos = target.slotToWorld(cell);
            var state = level.getBlockState(pos);
            Status status;
            if (state.getBlock() instanceof MachineAddonBlock addon && !addon.getAddonSettings().extender()) status = Status.DONE;
            else status = state.canBeReplaced() ? Status.TODO : Status.BLOCKED;
            list.add(new Entry(pos, Kind.SLOT, status));
        }
        return list;
    }

    private static Status coreStatus(BlockState state) {
        if (state.getBlock() instanceof MachineCoreBlock core) {
            return core.getCoreQuality() >= coreTier ? Status.DONE : Status.LOW_TIER;
        }
        return state.canBeReplaced() ? Status.TODO : Status.BLOCKED;
    }

    private static Component summary() {
        int cores = 0, extenders = 0, blocked = 0, free = 0, filled = 0;
        for (var e : entries) {
            switch (e.status()) {
                case BLOCKED -> blocked++;
                case TODO, LOW_TIER -> {
                    if (e.kind() == Kind.CORE) cores++;
                    else if (e.kind() == Kind.EXTENDER) extenders++;
                    else free++;
                }
                case DONE -> {
                    if (e.kind() == Kind.SLOT) filled++;
                }
            }
        }
        var key = "message." + AddonPlanner.MOD_ID + ".hologram_status";
        return Component.translatable(key, cores, extenders, blocked, filled, filled + free);
    }

    public static void render(RenderLevelStageEvent event) {
        if (target == null || event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        var mc = Minecraft.getInstance();
        var pose = event.getPoseStack();
        var buffers = mc.renderBuffers().bufferSource();
        var cam = event.getCamera().getPosition();

        // filled ghosts (DebugRenderer moves the box by the camera itself)
        for (var e : entries) {
            var color = fill(e);
            if (color == null) continue;
            DebugRenderer.renderFilledBox(pose, buffers, box(e).inflate(-0.06), color[0], color[1], color[2], color[3]);
        }
        buffers.endBatch(RenderType.debugFilledBox());

        pose.pushPose();
        pose.translate(-cam.x, -cam.y, -cam.z);
        var lines = buffers.getBuffer(RenderType.lines());
        for (var e : entries) {
            var color = outline(e);
            if (color == null) continue;
            LevelRenderer.renderLineBox(pose, lines, box(e).inflate(e.kind() == Kind.SLOT ? -0.2 : 0.002), color[0], color[1], color[2], color[3]);
        }
        pose.popPose();
        buffers.endBatch(RenderType.lines());
    }

    private static AABB box(Entry e) {
        return new AABB(e.pos());
    }

    @Nullable
    private static float[] fill(Entry e) {
        if (e.status() == Status.DONE) return null;
        if (e.status() == Status.BLOCKED) return new float[]{1f, 0.15f, 0.15f, 0.25f};
        return switch (e.kind()) {
            case CORE -> e.status() == Status.LOW_TIER ? new float[]{1f, 0.85f, 0.1f, 0.3f} : new float[]{1f, 0.55f, 0.1f, 0.3f};
            case EXTENDER -> new float[]{0.2f, 0.8f, 1f, 0.35f};
            case SLOT -> null;
        };
    }

    @Nullable
    private static float[] outline(Entry e) {
        if (e.status() == Status.DONE) return null;
        if (e.status() == Status.BLOCKED) return new float[]{1f, 0.1f, 0.1f, 1f};
        return switch (e.kind()) {
            case CORE -> new float[]{1f, 0.6f, 0.1f, 0.9f};
            case EXTENDER -> new float[]{0.2f, 0.85f, 1f, 0.9f};
            case SLOT -> new float[]{1f, 0.76f, 0.1f, 0.85f}; // same yellow as Oritech's slot indicator
        };
    }
}
