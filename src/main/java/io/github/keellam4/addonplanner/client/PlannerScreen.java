package io.github.keellam4.addonplanner.client;

import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.math.Axis;
import io.github.keellam4.addonplanner.AddonPlanner;
import io.github.keellam4.addonplanner.machine.MachineCatalog;
import io.github.keellam4.addonplanner.machine.MachineInfo;
import io.github.keellam4.addonplanner.machine.PlanCache;
import io.github.keellam4.addonplanner.solver.Cell;
import io.github.keellam4.addonplanner.solver.LayoutSolver;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;
import rearth.oritech.init.BlockContent;
import rearth.oritech.util.Geometry;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Pick a machine and core tier, see the best extender layout in 3D, and project it onto your machine. */
public class PlannerScreen extends Screen {

    private static final String P = "gui." + AddonPlanner.MOD_ID + ".";
    private static final int PANEL_W = 170;
    private static final int COLOR_EXTENDER = 0xFF33CCFF;
    private static final int COLOR_SLOT = 0xFF4DFF66;
    private static final int COLOR_CORE = 0xFFFF8C1A;

    @Nullable
    private final TargetedMachine target;
    private final List<MachineInfo> machines;
    private int machineIndex;
    private int coreTier = 1;
    private boolean layered = PlanCache.layeredDefault();
    private int extenders = -1; // -1 = best
    @Nullable
    private Integer layer;     // null = all layers
    private boolean showCores = true;

    private float yaw = 225, pitch = 30, zoom = 1;
    private boolean dragging;

    private CompletableFuture<List<LayoutSolver.Plan>> plans;
    private final List<PreviewBlock> preview = new ArrayList<>();
    private boolean previewDirty = true;
    @Nullable
    private BlockEntity machineEntity;
    private boolean machineEntityBroken;

    private Button machineButton, tierButton, extenderButton, layeredButton, layerButton, coresButton, worldButton;

    private record PreviewBlock(BlockState state, @Nullable BlockEntity entity, Vec3i offset) {
    }

    public PlannerScreen(@Nullable TargetedMachine target) {
        super(Component.translatable(P + "title"));
        this.target = target;
        this.machines = MachineCatalog.get();
        if (target != null) {
            machineIndex = Math.max(0, machines.indexOf(target.machine()));
            coreTier = Math.max(1, Math.min(7, (int) target.quality()));
        }
    }

    /** Open on a given machine and core tier (used by the smoke test). */
    public void select(MachineInfo info, int tier) {
        machineIndex = Math.max(0, machines.indexOf(info));
        coreTier = tier;
    }

    @Override
    protected void init() {
        var x = 8;
        var y = 26;
        var w = PANEL_W - 16;
        addRenderableWidget(Button.builder(Component.literal("<"), b -> cycleMachine(-1)).bounds(x, y, 16, 20).build());
        machineButton = addRenderableWidget(Button.builder(Component.empty(), b -> cycleMachine(1)).bounds(x + 18, y, w - 36, 20).build());
        addRenderableWidget(Button.builder(Component.literal(">"), b -> cycleMachine(1)).bounds(x + w - 16, y, 16, 20).build());
        y += 24;
        tierButton = addRenderableWidget(Button.builder(Component.empty(), b -> {
            coreTier = Math.floorMod(coreTier - 1 + (hasShiftDown() ? -1 : 1), 7) + 1;
            changed();
        }).bounds(x, y, w, 20).build());
        tierButton.setTooltip(Tooltip.create(Component.translatable(P + "core_tier.tooltip")));
        y += 24;
        extenderButton = addRenderableWidget(Button.builder(Component.empty(), b -> cycleExtenders(hasShiftDown() ? -1 : 1)).bounds(x, y, w, 20).build());
        extenderButton.setTooltip(Tooltip.create(Component.translatable(P + "extenders.tooltip")));
        y += 24;
        layeredButton = addRenderableWidget(Button.builder(Component.empty(), b -> {
            layered = !layered;
            changed();
        }).bounds(x, y, w, 20).build());
        layeredButton.setTooltip(Tooltip.create(Component.translatable(P + "layered.tooltip")));
        y += 24;
        layerButton = addRenderableWidget(Button.builder(Component.empty(), b -> cycleLayer(hasShiftDown() ? -1 : 1)).bounds(x, y, w / 2 - 1, 20).build());
        coresButton = addRenderableWidget(Button.builder(Component.empty(), b -> {
            showCores = !showCores;
            previewDirty = true;
        }).bounds(x + w / 2 + 1, y, w / 2 - 1, 20).build());

        worldButton = addRenderableWidget(Button.builder(Component.empty(), b -> toggleHologram()).bounds(x, height - 52, w, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose()).bounds(x, height - 28, w, 20).build());
        changed();
    }

    // ----- state -----

    private MachineInfo machine() {
        return machines.get(machineIndex);
    }

    private float quality() {
        return machine().usesCores() ? coreTier : machine().fixedQuality();
    }

    private void cycleMachine(int dir) {
        if (machines.isEmpty()) return;
        machineIndex = Math.floorMod(machineIndex + dir, machines.size());
        machineEntity = null;
        machineEntityBroken = false;
        layer = null;
        changed();
    }

    private void cycleExtenders(int dir) {
        var list = plans == null ? null : plans.getNow(null);
        if (list == null) return;
        var current = extenders < 0 ? list.indexOf(LayoutSolver.best(list)) : extenders;
        extenders = Math.floorMod(current + dir, list.size());
        previewDirty = true;
    }

    private void cycleLayer(int dir) {
        var range = layerRange();
        if (range == null) return;
        // cycle: all -> lowest .. highest -> all
        var options = new ArrayList<Integer>();
        options.add(null);
        for (int y = range[0]; y <= range[1]; y++) options.add(y);
        layer = options.get(Math.floorMod(options.indexOf(layer) + dir, options.size()));
    }

    private void changed() {
        extenders = -1;
        if (!machines.isEmpty()) plans = PlanCache.get(machine(), quality(), layered);
        previewDirty = true;
    }

    @Nullable
    private LayoutSolver.Plan plan() {
        var list = plans == null ? null : plans.getNow(null);
        if (list == null) return null;
        if (extenders < 0 || extenders >= list.size()) return LayoutSolver.best(list);
        return list.get(extenders);
    }

    private boolean targetMatches() {
        return target != null && !machines.isEmpty() && target.machine() == machine();
    }

    private void toggleHologram() {
        if (!targetMatches()) return;
        if (Hologram.isShowing(target)) {
            Hologram.clear();
        } else {
            var plan = plan();
            if (plan == null) return;
            Hologram.show(target, plan, machine().usesCores() ? coreTier : 0);
            onClose();
        }
    }

    // ----- preview contents -----

    private void rebuildPreview(LayoutSolver.Plan plan) {
        preview.clear();
        var info = machine();
        var facing = info.facing();
        for (var cell : info.shape().occupied()) {
            if (cell.equals(Cell.ORIGIN)) {
                preview.add(new PreviewBlock(info.block().defaultBlockState(), machineEntity(info.block()), offset(cell)));
            } else if (showCores) {
                preview.add(new PreviewBlock(coreBlock(coreTier).defaultBlockState(), null, offset(cell)));
            }
        }
        for (var cell : plan.result().extenders()) {
            preview.add(new PreviewBlock(BlockContent.MACHINE_EXTENDER.defaultBlockState(), null, offset(cell)));
        }
        for (var cell : plan.result().addonSlots()) {
            preview.add(new PreviewBlock(BlockContent.ADDON_INDICATOR_BLOCK.defaultBlockState(), null, offset(cell)));
        }
        previewDirty = false;
    }

    // draw in the frame of the machine's default block state, so its model lines up with the slots
    private Vec3i offset(Cell cell) {
        return Geometry.rotatePosition(new Vec3i(cell.x(), cell.y(), cell.z()), machine().facing());
    }

    @Nullable
    private BlockEntity machineEntity(Block block) {
        if (machineEntityBroken) return null;
        if (machineEntity == null && block instanceof EntityBlock entityBlock) {
            try {
                machineEntity = entityBlock.newBlockEntity(BlockPos.ZERO, block.defaultBlockState());
                if (machineEntity != null && minecraft != null) machineEntity.setLevel(minecraft.level);
            } catch (Throwable t) {
                machineEntityBroken = true;
            }
        }
        return machineEntity;
    }

    static Block coreBlock(int tier) {
        return switch (tier) {
            case 2 -> BlockContent.MACHINE_CORE_2;
            case 3 -> BlockContent.MACHINE_CORE_3;
            case 4 -> BlockContent.MACHINE_CORE_4;
            case 5 -> BlockContent.MACHINE_CORE_5;
            case 6 -> BlockContent.MACHINE_CORE_6;
            case 7 -> BlockContent.MACHINE_CORE_7;
            default -> BlockContent.MACHINE_CORE_1;
        };
    }

    @Nullable
    private int[] layerRange() {
        if (preview.isEmpty()) return null;
        int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE;
        for (var b : preview) {
            min = Math.min(min, b.offset().getY());
            max = Math.max(max, b.offset().getY());
        }
        return new int[]{min, max};
    }

    // ----- rendering -----

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        var plan = plan();
        if (plan != null && previewDirty) rebuildPreview(plan);
        updateLabels(plan);
        super.render(g, mouseX, mouseY, partialTick);

        g.drawString(font, title, 8, 10, 0xFFFFFF);
        if (machines.isEmpty()) {
            g.drawCenteredString(font, Component.translatable(P + "no_machines"), width / 2, height / 2, 0xFF5555);
            return;
        }

        var px = PANEL_W;
        g.fill(px, 8, width - 8, height - 8, 0x66000000);
        if (plan == null) {
            g.drawCenteredString(font, Component.translatable(P + "solving"), (px + width) / 2, height / 2, 0xAAAAAA);
        } else {
            renderPreview(g, px, 8, width - 8 - px, height - 16, partialTick);
        }
        renderInfo(g, plan);
    }

    private void updateLabels(@Nullable LayoutSolver.Plan plan) {
        if (machines.isEmpty()) return;
        var info = machine();
        machineButton.setMessage(info.block().getName());
        tierButton.visible = info.usesCores();
        tierButton.setMessage(Component.translatable(P + "core_tier", coreTier));
        var list = plans == null ? null : plans.getNow(null);
        extenderButton.active = list != null;
        extenderButton.setMessage(plan == null ? Component.translatable(P + "solving")
                : Component.translatable(P + "extenders", plan.extenderCount(), list.size() - 1));
        layeredButton.setMessage(Component.translatable(P + (layered ? "layered.on" : "layered.off")));
        layerButton.setMessage(layer == null ? Component.translatable(P + "layer.all") : Component.translatable(P + "layer", layer));
        coresButton.visible = info.usesCores();
        coresButton.setMessage(Component.translatable(P + (showCores ? "cores.shown" : "cores.hidden")));

        worldButton.active = targetMatches() && plan != null;
        var showing = targetMatches() && Hologram.isShowing(target);
        worldButton.setMessage(Component.translatable(P + (showing ? "hide_in_world" : "show_in_world")));
        worldButton.setTooltip(targetMatches() ? null : Tooltip.create(Component.translatable(P + "show_in_world.needs_target")));
    }

    private void renderInfo(GuiGraphics g, @Nullable LayoutSolver.Plan plan) {
        var x = 8;
        var y = 26 + 24 * 5 + 4;
        var w = PANEL_W - 16;
        var info = machine();
        var lines = new ArrayList<Component>();
        if (plan != null) {
            lines.add(Component.translatable(P + "info.slots", plan.addonSlots()).withStyle(ChatFormatting.GREEN));
            lines.add(Component.translatable(P + "info.quality", (int) quality(), LayoutSolver.maxExtenders(quality(), layered)));
            lines.add(Component.translatable(P + "info.needs").withStyle(ChatFormatting.GRAY));
            if (plan.extenderCount() > 0)
                lines.add(Component.translatable(P + "info.item", plan.extenderCount(), BlockContent.MACHINE_EXTENDER.getName()));
            if (info.usesCores())
                lines.add(Component.translatable(P + "info.item", info.shape().occupied().size() - 1, coreBlock(coreTier).getName()));
            if (plan.slotsBelowMachine() > 0)
                lines.add(Component.translatable(P + "info.below", plan.slotsBelowMachine()).withStyle(ChatFormatting.YELLOW));
            if (plan.extenderCount() > 0)
                lines.add(Component.translatable(P + "info.capacitor_tip").withStyle(ChatFormatting.DARK_GRAY));
            if (layered) lines.add(Component.translatable(P + "info.layered_warning").withStyle(ChatFormatting.YELLOW));
        }
        for (var line : lines) {
            for (var part : font.split(line, w)) {
                if (y > height - 70) return;
                g.drawString(font, part, x, y, 0xFFFFFF);
                y += 10;
            }
        }
        // legend
        y += 4;
        y = legend(g, x, y, COLOR_EXTENDER, BlockContent.MACHINE_EXTENDER.getName());
        y = legend(g, x, y, COLOR_SLOT, Component.translatable(P + "legend.slot"));
        if (info.usesCores()) legend(g, x, y, COLOR_CORE, Component.translatable(P + "legend.core"));
    }

    private int legend(GuiGraphics g, int x, int y, int color, Component text) {
        if (y > height - 66) return y;
        g.fill(x, y + 1, x + 7, y + 8, color);
        g.drawString(font, text, x + 11, y, 0xCCCCCC);
        return y + 11;
    }

    private void renderPreview(GuiGraphics g, int x, int y, int w, int h, float partialTick) {
        var blocks = layer == null ? preview : preview.stream().filter(b -> b.offset().getY() == layer).toList();
        if (blocks.isEmpty()) return;

        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
        for (var b : preview) { // frame on the whole plan so slicing doesn't jump
            var o = b.offset();
            minX = Math.min(minX, o.getX()); maxX = Math.max(maxX, o.getX() + 1);
            minY = Math.min(minY, o.getY()); maxY = Math.max(maxY, o.getY() + 1);
            minZ = Math.min(minZ, o.getZ()); maxZ = Math.max(maxZ, o.getZ() + 1);
        }
        float cx = (minX + maxX) / 2, cy = (minY + maxY) / 2, cz = (minZ + maxZ) / 2;
        var radius = (float) Math.sqrt((maxX - minX) * (maxX - minX) + (maxY - minY) * (maxY - minY) + (maxZ - minZ) * (maxZ - minZ)) / 2;
        var scale = Math.min(w, h) / 2f / Math.max(1, radius) * 0.95f * zoom;

        var mc = Minecraft.getInstance();
        var pose = g.pose();
        g.enableScissor(x, y, x + w, y + h);
        pose.pushPose();
        pose.translate(x + w / 2f, y + h / 2f, 400);
        pose.scale(scale, -scale, scale);
        pose.mulPose(Axis.XP.rotationDegrees(pitch));
        pose.mulPose(Axis.YP.rotationDegrees(yaw));
        pose.translate(-cx, -cy, -cz);

        RenderSystem.enableDepthTest();
        var buffers = mc.renderBuffers().bufferSource();
        RenderSystem.setShaderLights(new Vector3f(-1.5f, -0.5f, 0), new Vector3f(0, -1, 0));
        for (var b : blocks) {
            pose.pushPose();
            pose.translate(b.offset().getX(), b.offset().getY(), b.offset().getZ());
            if (b.state().getRenderShape() != RenderShape.ENTITYBLOCK_ANIMATED) {
                mc.getBlockRenderer().renderSingleBlock(b.state(), pose, buffers, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
            }
            if (b.entity() != null) renderEntity(b.entity(), partialTick, g, buffers);
            pose.popPose();
        }
        buffers.endBatch();
        Lighting.setupFor3DItems();
        pose.popPose();
        g.disableScissor();
    }

    private void renderEntity(BlockEntity entity, float partialTick, GuiGraphics g, net.minecraft.client.renderer.MultiBufferSource buffers) {
        try {
            var renderer = Minecraft.getInstance().getBlockEntityRenderDispatcher().getRenderer(entity);
            if (renderer != null) renderer.render(entity, partialTick, g.pose(), buffers, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
        } catch (Throwable t) {
            AddonPlanner.LOG.warn("Can't draw {} in the planner: {}", machine().id(), t.toString());
            machineEntityBroken = true;
            machineEntity = null;
            previewDirty = true;
        }
    }

    // ----- input -----

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) return true;
        if (mouseX > PANEL_W) {
            dragging = true;
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        dragging = false;
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (dragging) {
            yaw += (float) dragX * 0.8f;
            pitch = Math.max(-90, Math.min(90, pitch + (float) dragY * 0.8f));
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (mouseX > PANEL_W) {
            zoom = (float) Math.max(0.3, Math.min(5, zoom * Math.pow(1.12, scrollY)));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
