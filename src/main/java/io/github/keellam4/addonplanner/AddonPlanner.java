package io.github.keellam4.addonplanner;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.keellam4.addonplanner.client.Hologram;
import io.github.keellam4.addonplanner.client.PlannerScreen;
import io.github.keellam4.addonplanner.client.TargetedMachine;
import io.github.keellam4.addonplanner.machine.MachineCatalog;
import io.github.keellam4.addonplanner.machine.PlanCache;
import io.github.keellam4.addonplanner.solver.LayoutSolver;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.minecraft.client.gui.screens.TitleScreen;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(value = AddonPlanner.MOD_ID, dist = Dist.CLIENT)
public final class AddonPlanner {

    public static final String MOD_ID = "oritech_addon_planner";
    public static final Logger LOG = LoggerFactory.getLogger("Oritech Addon Planner");

    private static final String CATEGORY = "key.categories." + MOD_ID;
    public static final KeyMapping OPEN_PLANNER = new KeyMapping("key." + MOD_ID + ".open", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_P, CATEGORY);
    public static final KeyMapping TOGGLE_HOLOGRAM = new KeyMapping("key." + MOD_ID + ".hologram", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, CATEGORY);

    private static boolean smokeTestDone;

    public AddonPlanner(IEventBus modBus, ModContainer container) {
        modBus.addListener(RegisterKeyMappingsEvent.class, e -> {
            e.register(OPEN_PLANNER);
            e.register(TOGGLE_HOLOGRAM);
        });
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, e -> onClientTick());
        NeoForge.EVENT_BUS.addListener(RenderLevelStageEvent.class, Hologram::render);
        if (Boolean.getBoolean(MOD_ID + ".smokeTest")) {
            NeoForge.EVENT_BUS.addListener(ScreenEvent.Init.Post.class, e -> {
                if (e.getScreen() instanceof TitleScreen && !smokeTestDone) {
                    smokeTestDone = true;
                    SmokeTest.run();
                    Minecraft.getInstance().stop();
                }
            });
        }
    }

    private static void onClientTick() {
        var mc = Minecraft.getInstance();
        if (mc.player == null) return;

        while (OPEN_PLANNER.consumeClick()) {
            var target = TargetedMachine.find(mc);
            mc.setScreen(new PlannerScreen(target));
        }
        while (TOGGLE_HOLOGRAM.consumeClick()) {
            if (Hologram.isActive()) {
                Hologram.clear();
                mc.player.displayClientMessage(Component.translatable("message." + MOD_ID + ".hologram_off"), true);
                continue;
            }
            var target = TargetedMachine.find(mc);
            if (target == null) {
                mc.player.displayClientMessage(Component.translatable("message." + MOD_ID + ".look_at_machine"), true);
                continue;
            }
            var plans = PlanCache.get(target.machine(), target.quality(), PlanCache.layeredDefault()).join();
            Hologram.show(target, LayoutSolver.best(plans));
        }
        Hologram.tick(mc);
    }

    private static final class SmokeTest {
        static void run() {
            LOG.info("[smoke test] Reading machines");
            var machines = MachineCatalog.get();
            LOG.info("[smoke test] Found {} Oritech machines with addon slots", machines.size());
            for (var m : machines) {
                var qualities = m.usesCores() ? java.util.List.of(1f, 7f) : java.util.List.of(m.fixedQuality());
                for (var q : qualities) {
                    LOG.info("[smoke test] Solving {} quality {}", m.id(), q);
                    var plans = PlanCache.get(m, q, false).join();
                    var best = LayoutSolver.best(plans);
                    LOG.info("[smoke test] {} quality {}: {} addon slots with {} extenders", m.id(), q, best.addonSlots(), best.extenderCount());
                }
            }
            LOG.info("[smoke test] Title screen reached");
        }
    }
}
