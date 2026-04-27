package com.lootrunbot;

import baritone.api.BaritoneAPI;
import baritone.api.pathing.goals.Goal;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.wynntils.core.components.Models;
import com.wynntils.core.events.EventBusWrapper;
import com.wynntils.models.lootrun.beacons.LootrunBeaconKind;
import com.wynntils.models.lootrun.event.LootrunTaskFinishedEvent;
import com.wynntils.models.lootrun.event.LootrunTaskStartedEvent;
import com.wynntils.models.lootrun.type.TaskPrediction;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.neoforged.bus.api.SubscribeEvent;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * LootrunBot
 *
 * Notes:
 * - Uses press+release logic for right-click so the server receives distinct packets (required for R-R-R mage teleport).
 * - Uses Wynntils Models to pick beacons.
 * - Uses Baritone's custom goal to move toward targets when LOS is blocked.
 */
public class LootrunBot implements ClientModInitializer {

    // ─── Config ──────────────────────────────────────────────────────────────

    public static class Config {
        public double teleportRange       = 20.0;  // mage teleport range per cast
        public int    castIntervalTicks   = 8;     // ticks between starting a teleport combo
        public int    spellClickGapTicks  = 2;     // ticks between clicks inside the 3-click combo
        public double arrivalDistance     = 4.0;   // how close is "arrived"
        public String beaconPriority      = "ORANGE,RAINBOW,RED,AQUA"; // default priority (longevity)
        public boolean useBaritoneWhenBlocked = true;
        public boolean autoCombatEnabled  = true;
        public int     combatSpellInterval = 6;    // ticks between triggering combat macro
        public int     useKeyHoldTicks    = 2;     // how many ticks to hold useKey for each click
        public int     debugLogging       = 0;     // 0=off,1=info,2=verbose
    }

    private static Config config = new Config();
    private static final Path CONFIG_PATH = Path.of("config/lootrunbot.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static void saveConfig() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            Files.writeString(CONFIG_PATH, GSON.toJson(config));
        } catch (IOException e) { e.printStackTrace(); }
    }

    private static void loadConfig() {
        try {
            if (Files.exists(CONFIG_PATH)) {
                config = GSON.fromJson(Files.readString(CONFIG_PATH), Config.class);
            }
        } catch (IOException e) { e.printStackTrace(); }
    }

    // ─── State ───────────────────────────────────────────────────────────────

    private enum State { IDLE, MOVING, IN_CHALLENGE }

    private static boolean running = false;
    private static State state = State.IDLE;
    private static Vec3d target = null;
    private static LootrunBeaconKind chosenColor = null;
    private static boolean baritoneActive = false;

    // teleport/click combo tracking
    private static int ticksSinceCast = 0;
    private static int spellStep = 0;    // 0=idle, 1..3 = combo steps
    private static int spellTick = 0;    // ticks between clicks in combo

    // useKey press-release scheduling (so server sees discrete clicks)
    private static int useKeyReleaseTicks = 0;

    // combat macro
    private static int combatTickCounter = 0;

    // keybind
    private static KeyBinding toggleKey;

    // ─── Initialization ──────────────────────────────────────────────────────

    @Override
    public void onInitializeClient() {
        loadConfig();

        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.lootrunbot.toggle",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_F8,
                "LootrunBot"
        ));

        // Register this instance to Wynntils' event bus wrapper
        EventBusWrapper.registerEventListener(this);

        // Commands: /lb show, /lb set <key> <value>, /lb toggle
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(ClientCommandManager.literal("lb")
                    .then(ClientCommandManager.literal("show")
                            .executes(ctx -> {
                                msg("Teleport range: " + config.teleportRange);
                                msg("Cast interval ticks: " + config.castIntervalTicks);
                                msg("Spell click gap ticks: " + config.spellClickGapTicks);
                                msg("Arrival distance: " + config.arrivalDistance);
                                msg("Priority: " + config.beaconPriority);
                                msg("Running: " + running);
                                return 1;
                            }))
                    .then(ClientCommandManager.literal("set")
                            .then(ClientCommandManager.argument("key", com.mojang.brigadier.arguments.StringArgumentType.word())
                                    .then(ClientCommandManager.argument("value", com.mojang.brigadier.arguments.StringArgumentType.greedyString())
                                            .executes(ctx -> {
                                                String key = com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "key");
                                                String val = com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "value");
                                                applyConfig(key, val);
                                                saveConfig();
                                                return 1;
                                            }))))
                    .then(ClientCommandManager.literal("toggle")
                            .executes(ctx -> {
                                toggle();
                                return 1;
                            }))
            );
        });

        // tick loop
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null) return;

            // release scheduled useKey presses (must happen every tick before logic)
            if (useKeyReleaseTicks > 0) {
                useKeyReleaseTicks--;
                if (useKeyReleaseTicks == 0) {
                    MinecraftClient.getInstance().options.useKey.setPressed(false);
                }
            }

            // toggle via key
            while (toggleKey.wasPressed()) toggle();

            if (running) tick(client);
        });
    }

    private static void applyConfig(String key, String val) {
        try {
            switch (key.toLowerCase()) {
                case "teleportrange" -> { config.teleportRange = Double.parseDouble(val); msg("Teleport range = " + config.teleportRange); }
                case "castinterval" -> { config.castIntervalTicks = Integer.parseInt(val); msg("Cast interval ticks = " + config.castIntervalTicks); }
                case "spellclickgap" -> { config.spellClickGapTicks = Integer.parseInt(val); msg("Spell click gap ticks = " + config.spellClickGapTicks); }
                case "arrivaldistance" -> { config.arrivalDistance = Double.parseDouble(val); msg("Arrival distance = " + config.arrivalDistance); }
                case "priority" -> { config.beaconPriority = val.toUpperCase(); msg("Beacon priority = " + config.beaconPriority); }
                case "debug" -> { config.debugLogging = Integer.parseInt(val); msg("Debug = " + config.debugLogging); }
                default -> msg("Unknown key: " + key + " (valid: teleportRange, castInterval, spellClickGap, arrivalDistance, priority, debug)");
            }
        } catch (NumberFormatException e) {
            msg("Invalid numeric value: " + val);
        }
    }

    // ─── Toggle ──────────────────────────────────────────────────────────────

    private static void toggle() {
        running = !running;
        state = State.IDLE;
        target = null;
        stopBaritone();

        // ensure use key is released to avoid stuck state
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc != null) {
            mc.options.useKey.setPressed(false);
        }
        useKeyReleaseTicks = 0;

        msg(running ? "ON - F8 to stop" : "OFF");
    }

    // ─── Main tick ───────────────────────────────────────────────────────────

    private static void tick(MinecraftClient mc) {
        // Combat macro tick
        if (state == State.IN_CHALLENGE && config.autoCombatEnabled) {
            combatTickCounter++;
            if (combatTickCounter >= config.combatSpellInterval) {
                triggerCombatMacro(mc);
                combatTickCounter = 0;
            }
        }

        switch (state) {
            case IDLE -> {
                Map<LootrunBeaconKind, TaskPrediction> beacons = Models.Lootrun.getBeacons();
                if (beacons == null || beacons.isEmpty()) return;

                // choose beacon using simple priority order from config
                chosenColor = pickBest(beacons);
                TaskPrediction task = beacons.get(chosenColor);
                if (task == null) { state = State.IDLE; return; }

                target = new Vec3d(task.taskLocation().location().x(), task.taskLocation().location().y(), task.taskLocation().location().z());
                msg("Going to " + chosenColor.name() + " at " + fmt(target));
                state = State.MOVING;
                ticksSinceCast = config.castIntervalTicks; // allow immediate cast
            }

            case MOVING -> {
                if (target == null) { state = State.IDLE; return; }

                Vec3d pos = mc.player.getPos();
                double dist = pos.distanceTo(target);

                if (dist <= config.arrivalDistance) {
                    msg("Arrived!");
                    target = null;
                    state = State.IDLE;
                    stopBaritone();
                    return;
                }

                // Refresh beacon target if Wynntils updated it
                Map<LootrunBeaconKind, TaskPrediction> beacons = Models.Lootrun.getBeacons();
                if (beacons != null && chosenColor != null && beacons.containsKey(chosenColor)) {
                    TaskPrediction t = beacons.get(chosenColor);
                    target = new Vec3d(t.taskLocation().location().x(), t.taskLocation().location().y(), t.taskLocation().location().z());
                }

                Vec3d step = stepToward(pos, target);
                boolean los = hasLOS(mc, pos, step);

                if (los && isAccurateTeleportTarget(mc, step)) {
                    stopBaritone();
                    aimAt(mc, step);
                    doTeleportCombo(mc);
                } else {
                    if (!baritoneActive && config.useBaritoneWhenBlocked) {
                        startBaritoneWithTeleport(target);
                    }
                }
            }

            case IN_CHALLENGE -> {
                // waiting for events
            }
        }
    }

    // ─── Wynntils events ─────────────────────────────────────────────────────

    @SubscribeEvent
    public void onTaskStarted(LootrunTaskStartedEvent e) {
        if (!running) return;
        state = State.IN_CHALLENGE;
        target = null;
        stopBaritone();
        msg("Challenge started!");
    }

    @SubscribeEvent
    public void onTaskFinished(LootrunTaskFinishedEvent e) {
        if (!running) return;
        msg("Challenge finished!");
        state = State.IDLE;
    }

    // ─── Teleport combo (R-R-R) ──────────────────────────────────────────────

    private static void doTeleportCombo(MinecraftClient mc) {
        if (spellStep > 0) {
            spellTick++;
            if (spellTick >= config.spellClickGapTicks) {
                pressAndReleaseUse(mc);
                spellStep++;
                spellTick = 0;
                if (spellStep > 3) {
                    spellStep = 0;
                    ticksSinceCast = 0;
                }
            }
        } else {
            ticksSinceCast++;
            if (ticksSinceCast >= config.castIntervalTicks) {
                pressAndReleaseUse(mc);
                spellStep = 1;
                spellTick = 0;
            }
        }
    }

    /**
     * Press and schedule release of the use key so server registers a right-click packet.
     * We avoid staying in "held" state.
     */
    private static void pressAndReleaseUse(MinecraftClient mc) {
        mc.options.useKey.setPressed(true);
        useKeyReleaseTicks = config.useKeyHoldTicks;
    }

    // ─── Combat macro trigger ─────────────────────────────────────────────────

    private static void triggerCombatMacro(MinecraftClient mc) {
        // Prefer JSMacros or Robot; we simulate an OS-level G press via Robot as a fallback.
        try {
            java.awt.Robot robot = new java.awt.Robot();
            robot.keyPress(java.awt.event.KeyEvent.VK_G);
            Thread.sleep(40);
            robot.keyRelease(java.awt.event.KeyEvent.VK_G);
        } catch (Throwable t) {
            debug(1, "Failed to trigger G via Robot: " + t.getMessage());
        }
    }

    // ─── Movement helpers ────────────────────────────────────────────────────

    private static Vec3d stepToward(Vec3d from, Vec3d to) {
        double dist = from.distanceTo(to);
        if (dist <= config.teleportRange) return to;
        return from.add(to.subtract(from).normalize().multiply(config.teleportRange));
    }

    private static boolean hasLOS(MinecraftClient mc, Vec3d from, Vec3d to) {
        // sample 8 points between from and to and ensure no full solid blocks
        for (int i = 1; i <= 8; i++) {
            Vec3d p = from.lerp(to, i / 8.0);
            BlockPos bp = BlockPos.ofFloored(p);
            if (mc.world.getBlockState(bp).isSolidBlock(mc.world, bp)) return false;
        }
        return true;
    }

    private static boolean isAccurateTeleportTarget(MinecraftClient mc, Vec3d target) {
        BlockPos bp = BlockPos.ofFloored(target);
        return !mc.world.getBlockState(bp).isSolidBlock(mc.world, bp);
    }

    private static void aimAt(MinecraftClient mc, Vec3d pos) {
        Vec3d eyes = mc.player.getEyePos();
        double dx = pos.x - eyes.x;
        double dy = pos.y - eyes.y;
        double dz = pos.z - eyes.z;
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) Math.toDegrees(-Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        mc.player.setYaw(yaw);
        mc.player.setPitch(Math.max(-45f, Math.min(45f, pitch)));
    }

    // ─── Baritone Teleport Goal ──────────────────────────────────────────────

    public static class GoalMageTeleport implements Goal {
        private final BlockPos destination;
        private final double teleportRange;

        public GoalMageTeleport(BlockPos destination, double teleportRange) {
            this.destination = destination;
            this.teleportRange = teleportRange;
        }

        @Override
        public double heuristic(int x, int y, int z) {
            int dx = destination.getX() - x;
            int dy = destination.getY() - y;
            int dz = destination.getZ() - z;
            return Math.sqrt(dx * dx + dy * dy + dz * dz);
        }

        @Override
        public boolean isInGoal(int x, int y, int z) {
            return heuristic(x, y, z) <= teleportRange;
        }

        @Override
        public double getCost(int x, int y, int z) {
            return 0;
        }

        @Override
        public String toString() {
            return "GoalMageTeleport{" + destination + ", range=" + teleportRange + "}";
        }
    }

    private static void startBaritoneWithTeleport(Vec3d targetVec) {
        try {
            BlockPos targetPos = BlockPos.ofFloored(targetVec);
            BaritoneAPI.getProvider().getPrimaryBaritone()
                    .getCustomGoalProcess()
                    .setGoalAndPath(new GoalMageTeleport(targetPos, config.teleportRange));
            baritoneActive = true;
            debug(1, "Baritone started to " + fmt(targetVec));
        } catch (Exception e) {
            debug(1, "Baritone error: " + e.getMessage());
        }
    }

    private static void stopBaritone() {
        if (baritoneActive) {
            try {
                BaritoneAPI.getProvider().getPrimaryBaritone().getPathingBehavior().cancelEverything();
            } catch (Exception e) {
                debug(1, "Error stopping Baritone: " + e.getMessage());
            } finally {
                baritoneActive = false;
            }
        }
    }

    // ─── Beacon priority ─────────────────────────────────────────────────────

    private static LootrunBeaconKind pickBest(Map<LootrunBeaconKind, TaskPrediction> beacons) {
        // parse config beaconPriority: comma-separated list like "ORANGE,RAINBOW,RED,AQUA"
        String[] priority = config.beaconPriority.split(",");
        for (String p : priority) {
            String name = p.trim().toUpperCase();
            for (LootrunBeaconKind c : beacons.keySet()) {
                if (c.name().equalsIgnoreCase(name)) return c;
            }
        }
        // fallback to any beacon present
        return beacons.keySet().iterator().next();
    }

    // ─── Util ────────────────────────────────────────────────────────────────

    private static void msg(String text) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player != null) mc.player.sendMessage(Text.literal("§7[§6LB§7] " + text), false);
    }

    private static void debug(int level, String text) {
        if (config.debugLogging >= level) msg("[DEBUG] " + text);
    }

    private static String fmt(Vec3d v) {
        return (int)v.x + ", " + (int)v.y + ", " + (int)v.z;
    }
}
