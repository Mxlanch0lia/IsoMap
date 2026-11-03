package dev.isomap.smoke;

import com.mojang.blaze3d.platform.InputConstants;
import dev.isomap.IsoMap;
import dev.isomap.client.input.KeyBindings;
import dev.isomap.client.render.CameraAngles;
import dev.isomap.client.render.MapRuntime;
import dev.isomap.client.sound.UiSound;
import dev.isomap.client.ui.screen.MapScreen;
import dev.isomap.client.ui.screen.SettingsScreen;
import dev.isomap.client.ui.screen.WaypointEditScreen;
import dev.isomap.client.ui.screen.WaypointListScreen;
import dev.isomap.client.ui.widget.IsoButton;
import dev.isomap.client.ui.widget.IsoSlider;
import dev.isomap.client.ui.widget.IsoToggle;
import dev.isomap.client.ui.widget.TagChip;
import dev.isomap.client.waypoint.Waypoint;
import dev.isomap.client.waypoint.WaypointFilter;
import dev.isomap.client.waypoint.WaypointStore;
import dev.isomap.config.IsoMapConfig;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;
import net.minecraft.client.GraphicsStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.AccessibilityOnboardingScreen;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.gui.screens.GenericMessageScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import org.lwjgl.glfw.GLFW;

/**
 * Plays the mod inside a real client: runs through every acceptance item, takes screenshots and measures frame times.
 * Start with {@code gradlew runSmokeTest}; results land in {@code run-smoke/isomap-smoke/report.txt}
 * and {@code run-smoke/screenshots}. Inert unless the {@code isomap.smoke} system property is set.
 */
@EventBusSubscriber(modid = IsoMap.MOD_ID, value = Dist.CLIENT)
public final class SmokeTest {
    private static final boolean ACTIVE = Boolean.getBoolean("isomap.smoke");
    private static final String WORLD = "isomap_smoke";
    private static final long SEED = 20261031L;
    private static final int WATCHDOG_TICKS = 20 * 60 * 8;
    private static final int FRAME_SAMPLES = 300;
    private static final long SHUTDOWN_LIMIT_MS = 45_000L;
    private static final int SHUTDOWN_HANG_EXIT_CODE = 3;
    private static final int SETTLE_BEFORE_EXIT_TICKS = 300;
    private static final double MAX_GROUND_OFFSET = 12.0;
    private static final int TWO_CLIENT_PORT = 25599;
    private static final String GUEST_NAME = "Dev2";
    /** At the default sensitivity this is half a turn per second. */
    private static final double TURN_PIXELS_PER_SECOND = 720.0;
    private static final double QUARTER_TURN_PIXELS = 360.0;
    /** "caves": a short run with the turning measurement and real caves. "gallery": pictures for the mod's pages. */
    private static final String ONLY = System.getenv().getOrDefault("ISOMAP_SMOKE_ONLY", "");

    private static final Deque<Step> STEPS = new ArrayDeque<>();
    private static final List<String> LINES = new ArrayList<>();
    private static boolean started;
    private static boolean finished;
    private static boolean postingTestTurn;
    private static int hiddenChatPings;
    private static boolean turning;
    private static long lastTurn;
    private static int passed;
    private static int failed;
    private static int waitTicks;
    private static int stepTicks;
    private static int totalTicks;
    private static Step current;

    private static long frameStart;
    private static long frameSum;
    private static int frameCount;
    private static boolean sampling;

    private SmokeTest() {}

    private record Step(String name, BooleanSupplier ready, Runnable action, int timeoutTicks) {}

    private static void run(String name, Runnable action) {
        STEPS.add(new Step(name, () -> true, action, 0));
    }

    private static void waitFor(String name, BooleanSupplier ready, int timeoutTicks) {
        STEPS.add(new Step(name, ready, () -> {}, timeoutTicks));
    }

    private static void sleep(int ticks) {
        STEPS.add(new Step("sleep", () -> true, () -> waitTicks = ticks, 0));
    }

    private static void shot(String name) {
        // One tick of settling, so a screen opened by the previous step has been drawn at least once.
        sleep(6);
        run("screenshot " + name, () -> Screenshot.grab(FMLPaths.GAMEDIR.get().toFile(), name + ".png", mc().getMainRenderTarget(), m -> {}));
        sleep(2);
    }

    private static Minecraft mc() {
        return Minecraft.getInstance();
    }

    private static void check(String what, boolean ok) {
        if (ok) {
            passed++;
        } else {
            failed++;
        }
        log((ok ? "PASS  " : "FAIL  ") + what);
    }

    private static void log(String line) {
        LINES.add(line);
        IsoMap.LOGGER.info("[smoke] {}", line);
    }

    private static void command(String command) {
        MinecraftServer server = mc().getSingleplayerServer();
        server.execute(() -> server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), command));
    }

    private static void playerCommand(String command) {
        MinecraftServer server = mc().getSingleplayerServer();
        server.execute(() -> {
            var player = server.getPlayerList().getPlayers().get(0);
            server.getCommands().performPrefixedCommand(player.createCommandSourceStack().withPermission(4), command);
        });
    }

    @SubscribeEvent
    public static void onTick(ClientTickEvent.Post event) {
        if (!ACTIVE) {
            return;
        }
        Minecraft mc = mc();
        if (!started) {
            if (mc.getOverlay() != null) {
                return;
            }
            if (mc.screen instanceof AccessibilityOnboardingScreen) {
                mc.setScreen(new TitleScreen());
            }
            if (mc.screen instanceof TitleScreen) {
                started = true;
                if (ONLY.equals("caves")) {
                    planCavesOnly();
                } else if (ONLY.equals("host")) {
                    planHost();
                } else if (ONLY.equals("guest")) {
                    planGuest();
                } else if (ONLY.equals("dedicated")) {
                    planDedicated();
                } else if (ONLY.equals("compat")) {
                    planCompat();
                } else if (ONLY.equals("gallery")) {
                    planGallery();
                } else {
                    plan();
                }
            }
            return;
        }
        if (finished) {
            return;
        }
        if (++totalTicks > WATCHDOG_TICKS) {
            check("watchdog: finished in time", false);
            finish();
            return;
        }
        if (waitTicks > 0) {
            waitTicks--;
            return;
        }
        if (current == null) {
            current = STEPS.poll();
            stepTicks = 0;
            if (current == null) {
                finish();
                return;
            }
        }
        Step step = current;
        if (!step.ready.getAsBoolean()) {
            if (++stepTicks > step.timeoutTicks) {
                check("timed out waiting: " + step.name, false);
                current = null;
            }
            return;
        }
        current = null;
        try {
            step.action.run();
        } catch (Throwable t) {
            IsoMap.LOGGER.error("[smoke] step failed: {}", step.name, t);
            check("step threw " + t + ": " + step.name, false);
        }
    }

    /** Someone touching the mouse during a run must not turn the player and change the results. */
    @SubscribeEvent
    public static void onPlayerTurn(net.neoforged.neoforge.client.event.CalculatePlayerTurnEvent event) {
        if (ACTIVE && !postingTestTurn) {
            event.setMouseSensitivity(-1.0 / 3.0);
        }
    }

    @SubscribeEvent
    public static void onFramePre(RenderFrameEvent.Pre event) {
        if (ACTIVE && turning && mc().player != null) {
            long now = System.nanoTime();
            if (lastTurn != 0L) {
                CameraAngles.addMouseInput(TURN_PIXELS_PER_SECOND * (now - lastTurn) / 1.0e9, 0.0);
            }
            lastTurn = now;
        } else {
            lastTurn = 0L;
        }
        if (ACTIVE && sampling) {
            long now = System.nanoTime();
            if (frameStart != 0L) {
                frameSum += now - frameStart;
                frameCount++;
            }
            frameStart = now;
        }
    }

    private static void beginSampling() {
        frameStart = 0L;
        frameSum = 0L;
        frameCount = 0;
        sampling = true;
    }

    private static double endSampling() {
        sampling = false;
        return frameCount == 0 ? -1.0 : frameSum / 1.0e6 / frameCount;
    }

    private static void finish() {
        if (finished) {
            return;
        }
        finished = true;
        log("RESULT: " + (failed == 0 ? "PASS" : "FAIL") + "  passed=" + passed + " failed=" + failed);
        writeReport();
        startShutdownWatchdog();
        mc().stop();
    }

    private static void writeReport() {
        try {
            Path dir = FMLPaths.GAMEDIR.get().resolve("isomap-smoke");
            Files.createDirectories(dir);
            Files.write(dir.resolve("report.txt"), LINES, StandardCharsets.UTF_8);
        } catch (IOException e) {
            IsoMap.LOGGER.error("[smoke] could not write report", e);
        }
    }

    /** A test run must always end: a stuck exit is reported, with the server thread's stack, and the JVM is halted. */
    private static void startShutdownWatchdog() {
        MinecraftServer server = mc().getSingleplayerServer();
        Thread thread = new Thread(() -> {
            try {
                Thread.sleep(SHUTDOWN_LIMIT_MS);
            } catch (InterruptedException e) {
                return;
            }
            log("SHUTDOWN HANG: the client did not exit within " + SHUTDOWN_LIMIT_MS / 1000 + " s");
            Thread.getAllStackTraces().forEach((t, stack) -> {
                if (t.getName().equals("Server thread") || t.getName().equals("Render thread")) {
                    log("  thread " + t.getName() + ": " + java.util.Arrays.stream(stack).limit(6).map(Object::toString).toList());
                }
            });
            dumpServerState(server);
            writeReport();
            Runtime.getRuntime().halt(SHUTDOWN_HANG_EXIT_CODE);
        }, "IsoMap smoke shutdown watchdog");
        thread.setDaemon(true);
        thread.start();
    }

    /** What the server's stop loop is still waiting for: each term of {@code ChunkMap.hasWork()}. */
    private static void dumpServerState(MinecraftServer server) {
        if (server == null) {
            log("  no integrated server");
            return;
        }
        try {
            for (var level : server.getAllLevels()) {
                var map = level.getChunkSource().chunkMap;
                java.util.Map<?, ?> pending = (java.util.Map<?, ?>) field(net.minecraft.server.level.ChunkMap.class, map, "pendingUnloads");
                java.util.Map<?, ?> updating = (java.util.Map<?, ?>) field(net.minecraft.server.level.ChunkMap.class, map, "updatingChunkMap");
                java.util.Collection<?> toDrop = (java.util.Collection<?>) field(net.minecraft.server.level.ChunkMap.class, map, "toDrop");
                java.util.Collection<?> generation = (java.util.Collection<?>) field(net.minecraft.server.level.ChunkMap.class, map, "pendingGenerationTasks");
                Object distance = field(net.minecraft.server.level.ChunkMap.class, map, "distanceManager");
                java.util.Map<?, ?> tickets = (java.util.Map<?, ?>) field(net.minecraft.server.level.DistanceManager.class, distance, "tickets");
                log("  " + level.dimension().location() + ": hasWork=" + map.hasWork() + " pendingUnloads=" + pending.size() + " updating=" + updating.size()
                        + " toDrop=" + toDrop.size() + " pendingGeneration=" + generation.size() + " tickets=" + tickets.size());
                int shown = 0;
                for (Object value : pending.values()) {
                    var holder = (net.minecraft.server.level.ChunkHolder) value;
                    if (holder.getGenerationRefCount() != 0 && shown++ < 5) {
                        log("    stuck holder " + holder.getPos() + " refCount=" + holder.getGenerationRefCount()
                                + " status=" + holder.getPersistedStatus() + " ticketLevel=" + holder.getTicketLevel());
                    }
                }
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            log("  server state unavailable: " + e);
        }
    }

    private static Object field(Class<?> owner, Object instance, String name) throws ReflectiveOperationException {
        var field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(instance);
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (Files.exists(path)) {
            try (Stream<Path> walk = Files.walk(path)) {
                for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(p);
                }
            }
        }
    }

    private static List<GuiEventListener> children() {
        return new ArrayList<>(mc().screen.children());
    }

    private static <T> List<T> widgets(Class<T> type) {
        return children().stream().filter(type::isInstance).map(type::cast).toList();
    }

    private static void click(AbstractWidget widget, int button) {
        mc().screen.mouseClicked(widget.getX() + widget.getWidth() / 2.0, widget.getY() + widget.getHeight() / 2.0, button);
        mc().screen.mouseReleased(widget.getX() + widget.getWidth() / 2.0, widget.getY() + widget.getHeight() / 2.0, button);
    }

    private static Waypoint make(String name, int dx, int dz, int color, String... tags) {
        var player = mc().player;
        Waypoint w = new Waypoint();
        w.name = name;
        w.x = player.getBlockX() + dx;
        w.y = player.getBlockY();
        w.z = player.getBlockZ() + dz;
        w.color = color;
        w.dimension = player.level().dimension().location().toString();
        for (String tag : tags) {
            w.addTag(tag);
        }
        return w;
    }

    /** Settings screen with the pointer pinned over one button, to show the hover state without moving the real cursor. */
    private static final class PinnedPointerSettings extends SettingsScreen {
        PinnedPointerSettings() {
            super(null);
        }

        @Override
        public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            IsoButton first = (IsoButton) children().stream().filter(c -> c instanceof IsoButton).skip(1).findFirst().orElse(null);
            super.render(g, first == null ? 0 : first.getX() + 4, first == null ? 0 : first.getY() + 4, partialTick);
        }
    }

    private static void planCavesOnly() {
        planFreshWorld();
        planTurningSmoothness();
        planWaypointHeight();
        planFlatMap();
        planCaveViews();
        sleep(SETTLE_BEFORE_EXIT_TICKS);
    }

    /** Clean pictures for the mod's pages: no test data, no chat, English interface. */
    private static void planGallery() {
        Minecraft mc = mc();
        planFreshWorld();
        run("scene rules", () -> {
            command("gamerule sendCommandFeedback false");
            command("weather clear");
            command("gamerule doDaylightCycle false");
            mc.getToasts().clear();
        });
        sleep(200);
        run("scene waypoints", () -> {
            mc.gui.getChat().clearMessages(false);
            WaypointStore store = WaypointStore.get();
            store.add(make("Home", 14, -10, 0x6FB1FF, "base"));
            store.add(make("Mine", -22, 12, 0xFFB454, "resources"));
            Waypoint lookout = make("Lookout", 10, 16, 0x7BD88F, "explore");
            lookout.y += 28;
            store.add(lookout);
            store.add(make("Village", 40, 46, 0xF28FB3, "explore", "trade"));
            Waypoint death = make("Death", -8, -20, 0xE5655C, Waypoint.DEATH_TAG);
            death.death = true;
            store.add(death);
            IsoMapConfig.SIZE.set(170);
            IsoMapConfig.LABEL_MODE.set(dev.isomap.config.LabelMode.ALL);
            mc.player.setXRot(-4f);
        });
        sleep(40);
        shot("g1_minimap");
        run("turn", () -> CameraAngles.addMouseInput(150.0, -40.0));
        sleep(30);
        shot("g2_minimap_turned");
        run("untilt", CameraAngles::reset);
        run("map", () -> mc.setScreen(new MapScreen(null)));
        sleep(40);
        shot("g3_map");
        run("map turned", () -> CameraAngles.addMouseInput(200.0, 0.0));
        sleep(30);
        shot("g4_map_turned");
        run("list", () -> {
            CameraAngles.reset();
            mc.setScreen(new WaypointListScreen(null));
        });
        shot("g5_waypoints");
        run("edit", () -> mc.setScreen(WaypointEditScreen.forExisting(WaypointStore.get().all().get(0), null)));
        shot("g6_edit");
        run("settings", () -> {
            SettingsScreen.selectTab(SettingsScreen.Tab.MINIMAP);
            mc.setScreen(new SettingsScreen(null));
        });
        shot("g7_settings");
        run("mod list", () -> mc.setScreen(new net.neoforged.neoforge.client.gui.ModListScreen(new TitleScreen())));
        sleep(10);
        run("select mod", () -> {
            mc.screen.mouseClicked(30.0, 70.0, 0);
            mc.screen.mouseReleased(30.0, 70.0, 0);
        });
        shot("g8_mod_list");
        run("close", () -> mc.setScreen(null));
        BlockPos[] cave = new BlockPos[1];
        run("go caving", () -> {
            playerCommand("gamemode spectator");
            playerCommand("tp @s 320 100 40");
        });
        sleep(120);
        run("find", () -> {
            cave[0] = findCave(320, 40);
            if (cave[0] != null) {
                playerCommand("tp @s " + cave[0].getX() + " " + cave[0].getY() + " " + cave[0].getZ());
            }
        });
        sleep(160);
        shot("g9_cave");
        run("cave map", () -> mc.setScreen(new MapScreen(null)));
        sleep(40);
        shot("g10_cave_map");
        run("close", () -> mc.setScreen(null));
        sleep(SETTLE_BEFORE_EXIT_TICKS);
    }

    private static void planFreshWorld() {
        Minecraft mc = mc();
        run("configure client", () -> {
            mc.options.pauseOnLostFocus = false;
            mc.options.onboardAccessibility = false;
            mc.options.tutorialStep = net.minecraft.client.tutorial.TutorialSteps.NONE;
            mc.getTutorial().setStep(net.minecraft.client.tutorial.TutorialSteps.NONE);
            mc.getToasts().clear();
            mc.options.enableVsync().set(false);
            mc.getWindow().updateVsync(false);
            mc.options.framerateLimit().set(260);
            mc.getWindow().setFramerateLimit(260);
            mc.options.guiScale().set(2);
            mc.resizeDisplay();
            IsoMapConfig.resetAll();
            CameraAngles.reloadFromConfig();
            try {
                deleteRecursively(FMLPaths.GAMEDIR.get().resolve("saves").resolve(WORLD));
                deleteRecursively(FMLPaths.GAMEDIR.get().resolve("isomap"));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
        run("create world", () -> mc.createWorldOpenFlows().createFreshLevel(WORLD,
                new LevelSettings(WORLD, GameType.CREATIVE, false, Difficulty.NORMAL, true, new GameRules(), WorldDataConfiguration.DEFAULT),
                new WorldOptions(SEED, true, false),
                access -> access.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.NORMAL).value().createWorldDimensions(),
                new TitleScreen()));
        waitFor("in world", () -> mc.player != null && mc.level != null && mc.screen == null, 20 * 120);
        run("world rules", () -> {
            command("time set noon");
            command("gamerule doMobSpawning false");
        });
        waitFor("terrain uploaded", () -> MapRuntime.terrain().gpuBufferCount() > 4, 20 * 60);
    }

    private static void plan() {
        Minecraft mc = mc();
        run("configure client", () -> {
            mc.options.pauseOnLostFocus = false;
            mc.options.onboardAccessibility = false;
            // The first-run movement hint would otherwise sit on top of the map in every screenshot.
            mc.options.tutorialStep = net.minecraft.client.tutorial.TutorialSteps.NONE;
            mc.getTutorial().setStep(net.minecraft.client.tutorial.TutorialSteps.NONE);
            mc.getToasts().clear();
            mc.options.enableVsync().set(false);
            mc.getWindow().updateVsync(false);
            mc.options.framerateLimit().set(260);
            mc.getWindow().setFramerateLimit(260);
            mc.options.guiScale().set(2);
            mc.resizeDisplay();
            log("window " + mc.getWindow().getWidth() + "x" + mc.getWindow().getHeight()
                    + " guiScale=" + (int) mc.getWindow().getGuiScale() + " lang=" + mc.options.languageCode);
            mc.options.graphicsMode().set(GraphicsStatus.FANCY);
            IsoMapConfig.resetAll();
            CameraAngles.reloadFromConfig();
        });
        run("delete old world", () -> {
            try {
                deleteRecursively(FMLPaths.GAMEDIR.get().resolve("saves").resolve(WORLD));
                deleteRecursively(FMLPaths.GAMEDIR.get().resolve("isomap"));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });

        // ---- Assets and registration
        run("assets", () -> {
            for (String name : List.of("open", "close", "hover", "click", "toggle_on", "toggle_off", "waypoint_add", "waypoint_remove")) {
                ResourceLocation file = IsoMap.id("sounds/ui/" + name + ".ogg");
                check("sound file present: " + name, mc.getResourceManager().getResource(file).isPresent());
                check("sound event resolved: " + name, mc.getSoundManager().getSoundEvent(IsoMap.id("ui." + name)) != null);
            }
            check("nothing added to the synced sound registry", net.minecraft.core.registries.BuiltInRegistries.SOUND_EVENT.keySet().stream().noneMatch(k -> k.getNamespace().equals(IsoMap.MOD_ID)));
            check("all key mappings registered", Set.of(mc.options.keyMappings).containsAll(KeyBindings.ALL));
        });

        run("settings from title screen", () -> mc.setScreen(new SettingsScreen(new TitleScreen())));
        shot("00_settings_on_title");
        run("back to title", () -> mc.setScreen(new TitleScreen()));

        // ---- World
        run("create world", () -> {
            GameRules rules = new GameRules();
            LevelSettings settings = new LevelSettings(WORLD, GameType.CREATIVE, false, Difficulty.NORMAL, true, rules, WorldDataConfiguration.DEFAULT);
            WorldOptions options = new WorldOptions(SEED, true, false);
            mc.createWorldOpenFlows().createFreshLevel(WORLD, settings, options,
                    access -> access.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.NORMAL).value().createWorldDimensions(),
                    new TitleScreen());
        });
        waitFor("in world", () -> mc.player != null && mc.level != null && mc.screen == null, 20 * 120);
        run("world rules", () -> {
            command("time set noon");
            command("weather clear");
            command("gamerule doDaylightCycle false");
            command("gamerule doMobSpawning false");
            check("waypoint store opened for this world", WaypointStore.get().isOpen());
            log("waypoint file: " + WaypointStore.get().file());
        });
        waitFor("terrain sampled", () -> MapRuntime.terrain().snapshotCount() > 100, 20 * 60);
        waitFor("terrain uploaded", () -> MapRuntime.terrain().gpuBufferCount() > 4, 20 * 60);
        sleep(40);
        run("terrain state", () -> {
            check("chunks sampled", MapRuntime.terrain().snapshotCount() > 0);
            check("tiles on the GPU", MapRuntime.terrain().gpuBufferCount() > 0);
            log("terrain: chunks=" + MapRuntime.terrain().snapshotCount() + " gpuBuffers=" + MapRuntime.terrain().gpuBufferCount());
        });
        shot("01_hud_default");

        // ---- Camera
        double[] before = new double[2];
        run("camera yaw", () -> {
            before[0] = CameraAngles.targetYawOffset();
            CameraAngles.addMouseInput(180.0, 0.0);
            check("mouse X turns yaw", Math.abs(CameraAngles.targetYawOffset() - before[0]) > 10.0);
        });
        sleep(20);
        run("yaw applied", () -> check("drawn yaw follows the target", Math.abs(CameraAngles.yawDegrees() - CameraAngles.targetYawOffset()) < 2.0));
        shot("02_hud_rotated");
        run("camera pitch", () -> {
            before[1] = CameraAngles.targetPitch();
            CameraAngles.addMouseInput(0.0, -60.0);
            check("mouse Y tilts", CameraAngles.targetPitch() < before[1]);
            CameraAngles.addMouseInput(0.0, -100000.0);
            check("tilt clamps at the minimum", Math.abs(CameraAngles.targetPitch() - IsoMapConfig.PITCH_MIN.getAsInt()) < 1.0e-6);
        });
        sleep(20);
        shot("03_hud_low_pitch");
        run("pitch max", () -> {
            CameraAngles.addMouseInput(0.0, 100000.0);
            check("tilt clamps at the maximum", Math.abs(CameraAngles.targetPitch() - IsoMapConfig.PITCH_MAX.getAsInt()) < 1.0e-6);
            CameraAngles.reset();
            IsoMapConfig.YAW_LIMIT.set(30);
            CameraAngles.addMouseInput(100000.0, 0.0);
            check("rotation limit holds", Math.abs(CameraAngles.targetYawOffset() - 30.0) < 1.0e-6);
            IsoMapConfig.YAW_LIMIT.set(180);
            CameraAngles.reset();
            IsoMapConfig.INVERT_YAW.set(true);
            double start = CameraAngles.targetYawOffset();
            CameraAngles.addMouseInput(40.0, 0.0);
            check("yaw inversion", CameraAngles.targetYawOffset() < start);
            IsoMapConfig.INVERT_YAW.set(false);
            CameraAngles.reset();
        });
        run("top view", CameraAngles::toggleTopView);
        sleep(20);
        run("top view check", () -> check("top view looks straight down", Math.abs(CameraAngles.pitchDegrees() - 90.0) < 2.0));
        shot("04_hud_top_view");
        run("top view off", CameraAngles::toggleTopView);
        run("player turns", () -> {
            mc.player.setYRot(90f);
            mc.player.yRotO = 90f;
        });
        sleep(40);
        run("north stays up", () -> {
            double yaw = net.minecraft.util.Mth.wrapDegrees(CameraAngles.yawDegrees());
            check("the map does not turn with the player's view (" + Math.round(yaw) + ", camera key down: " + KeyBindings.CAMERA_HOLD.isDown() + ")", Math.abs(yaw) < 1.0);
            mc.player.setYRot(0f);
        });

        // ---- Zoom
        run("zoom in", () -> {
            double zoom = MapRuntime.hudView().zoomTarget();
            MapRuntime.hudView().multiplyZoom(2.0);
            check("zoom grows", MapRuntime.hudView().zoomTarget() > zoom);
        });
        shot("06_hud_zoom_in");
        run("zoom out", () -> MapRuntime.hudView().setZoomTarget(0.5));
        sleep(15);
        shot("07_hud_zoom_out");
        run("zoom reset", () -> MapRuntime.hudView().setZoomTarget(1.5));

        planTurningSmoothness();
        planWaypointHeight();
        planFlatMap();

        // ---- Wheel event path
        run("wheel keeps the hotbar without the camera key", () -> {
            var scroll = new net.neoforged.neoforge.client.event.InputEvent.MouseScrollingEvent(0.0, 1.0, false, false, false, 0.0, 0.0);
            double zoom = MapRuntime.hudView().zoomTarget();
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(scroll);
            check("scroll without the key is left to the game", !scroll.isCanceled() && MapRuntime.hudView().zoomTarget() == zoom);
        });

        // ---- Visibility
        run("camera key", () -> {
            KeyBindings.CAMERA_HOLD.setDown(true);
            log("camera key state: enabled=" + IsoMapConfig.ENABLED.getAsBoolean() + " screen=" + mc.screen + " down=" + KeyBindings.CAMERA_HOLD.isDown()
                    + " hudVisible=" + MapRuntime.isHudVisible(mc) + " focused=" + mc.isWindowActive());
            postingTestTurn = true;
            var turn = new net.neoforged.neoforge.client.event.CalculatePlayerTurnEvent(0.5, false);
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(turn);
            check("holding the camera key stops the player turning", Math.abs(turn.getMouseSensitivity() + 1.0 / 3.0) < 1.0e-9);
            var scroll = new net.neoforged.neoforge.client.event.InputEvent.MouseScrollingEvent(0.0, 1.0, false, false, false, 0.0, 0.0);
            double zoom = MapRuntime.hudView().zoomTarget();
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(scroll);
            check("the wheel zooms while the camera key is held", scroll.isCanceled() && MapRuntime.hudView().zoomTarget() > zoom);
            MapRuntime.hudView().setZoomTarget(zoom);
            KeyBindings.CAMERA_HOLD.setDown(false);
            var free = new net.neoforged.neoforge.client.event.CalculatePlayerTurnEvent(0.5, false);
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(free);
            check("the player turns normally once the key is released", free.getMouseSensitivity() == 0.5);
            postingTestTurn = false;
        });
        run("hide with the key", () -> net.minecraft.client.KeyMapping.click(KeyBindings.TOGGLE_VISIBLE.getKey()));
        sleep(3);
        run("hidden check", () -> check("the visibility key hides the map", !IsoMapConfig.ENABLED.getAsBoolean()));
        shot("08_hud_hidden");
        run("show with the key", () -> net.minecraft.client.KeyMapping.click(KeyBindings.TOGGLE_VISIBLE.getKey()));
        sleep(3);
        run("shown check", () -> check("the visibility key shows the map", IsoMapConfig.ENABLED.getAsBoolean()));
        for (var entry : java.util.Map.<net.minecraft.client.KeyMapping, Class<? extends Screen>>of(
                KeyBindings.OPEN_MAP, MapScreen.class,
                KeyBindings.OPEN_SETTINGS, SettingsScreen.class,
                KeyBindings.OPEN_WAYPOINTS, WaypointListScreen.class,
                KeyBindings.CREATE_WAYPOINT, WaypointEditScreen.class).entrySet()) {
            run("press " + entry.getKey().getName(), () -> net.minecraft.client.KeyMapping.click(entry.getKey().getKey()));
            sleep(3);
            run("check " + entry.getKey().getName(), () -> {
                check(entry.getKey().getName() + " opens " + entry.getValue().getSimpleName(), entry.getValue().isInstance(mc.screen));
                mc.setScreen(null);
            });
            sleep(3);
        }

        // ---- Waypoints: create, tag, filter, persist
        run("waypoints create", () -> {
            WaypointStore store = WaypointStore.get();
            store.add(make("Дом", 18, -12, 0x6FB1FF, "база", "home"));
            store.add(make("Шахта", -24, 10, 0xFFB454, "ресурсы"));
            store.add(make("Far away", 900, 900, 0x7BD88F, "explore", "home"));
            Waypoint hidden = make("Скрытая", 6, 6, 0xB48EFF);
            hidden.visible = false;
            store.add(hidden);
            check("waypoints stored", store.all().size() == 4);
            check("tags are normalised", make("t", 0, 0, 0, "  Some Tag ").tags.contains("some-tag"));
            check("duplicate tag rejected", !store.all().get(0).addTag("HOME"));
            check("tag list", store.allTags().containsAll(List.of("база", "home", "ресурсы", "explore")));
        });
        run("waypoints filter", () -> {
            List<Waypoint> all = WaypointStore.get().all();
            check("search by name", all.stream().filter(w -> WaypointFilter.matches(w, "шах", List.of(), null)).count() == 1);
            check("search by #tag", all.stream().filter(w -> WaypointFilter.matches(w, "#home", List.of(), null)).count() == 2);
            check("filter by tag", all.stream().filter(w -> WaypointFilter.matches(w, "", List.of("explore"), null)).count() == 1);
            check("filter by two tags", all.stream().filter(w -> WaypointFilter.matches(w, "", List.of("home", "база"), null)).count() == 1);
            check("filter by dimension", all.stream().filter(w -> WaypointFilter.matches(w, "", List.of(), "minecraft:the_nether")).count() == 0);
        });
        run("waypoints persist", () -> {
            WaypointStore store = WaypointStore.get();
            String firstId = store.all().get(0).id;
            store.reload();
            check("waypoints survive a re-read from disk", store.all().size() == 4 && store.byId(firstId) != null);
            check("cyrillic survives the file", "Дом".equals(store.byId(firstId).name));
            Waypoint edited = store.byId(firstId).copy();
            edited.name = "Дом 2";
            edited.addTag("edited");
            check("edits work on a copy", "Дом".equals(store.byId(firstId).name));
            store.put(edited);
            store.reload();
            check("edit persisted", "Дом 2".equals(store.byId(firstId).name) && store.byId(firstId).tags.contains("edited"));
            store.setVisible(store.byId(firstId), false);
            store.reload();
            check("hide persisted", !store.byId(firstId).visible);
            store.setVisible(store.byId(firstId), true);
            Waypoint temp = make("temp", 0, 0, 0);
            store.add(temp);
            store.remove(temp);
            store.reload();
            check("delete persisted", store.byId(temp.id) == null);
        });
        shot("09_hud_waypoints");

        // ---- Corrupt file: must not crash or lose the data silently
        run("corrupt file handling", () -> {
            WaypointStore store = WaypointStore.get();
            int count = store.all().size();
            try {
                Files.writeString(store.file(), "{ this is not json", StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            store.reload();
            check("corrupt file falls back to the backup", store.all().size() >= count - 1 && !store.all().isEmpty());
            log("after corruption: " + store.all().size() + " of " + count + " waypoints restored");
            store.put(store.all().get(0).copy());
        });

        // ---- Sounds
        run("sound switches", () -> {
            int plays = UiSound.CLICK.playCount();
            IsoMapConfig.SOUND_ENABLED.set(false);
            UiSound.CLICK.play();
            check("master switch mutes", UiSound.CLICK.playCount() == plays);
            IsoMapConfig.SOUND_ENABLED.set(true);
            IsoMapConfig.SOUND_CLICK.set(false);
            UiSound.CLICK.play();
            check("group switch mutes", UiSound.CLICK.playCount() == plays);
            IsoMapConfig.SOUND_CLICK.set(true);
            IsoMapConfig.SOUND_VOLUME.set(0);
            UiSound.CLICK.play();
            check("zero volume plays nothing", UiSound.CLICK.playCount() == plays);
            IsoMapConfig.SOUND_VOLUME.set(60);
        });
        sleep(4);
        run("sound plays", () -> {
            int plays = UiSound.CLICK.playCount();
            UiSound.CLICK.play();
            check("sound plays when enabled", UiSound.CLICK.playCount() == plays + 1);
        });
        sleep(4);
        run("hover cooldown", () -> {
            int plays = UiSound.HOVER.playCount();
            for (int i = 0; i < 20; i++) {
                UiSound.HOVER.play();
            }
            check("hover spam is limited by the cooldown", UiSound.HOVER.playCount() == plays + 1);
        });

        // ---- Settings screens, real widget clicks
        for (SettingsScreen.Tab tab : SettingsScreen.Tab.values()) {
            run("settings tab " + tab, () -> {
                SettingsScreen.selectTab(tab);
                mc.setScreen(new SettingsScreen(null));
            });
            shot("10_settings_" + tab.name().toLowerCase(Locale.ROOT));
        }
        run("settings widgets", () -> {
            SettingsScreen.selectTab(SettingsScreen.Tab.GENERAL);
            mc.setScreen(new SettingsScreen(null));
            boolean value = IsoMapConfig.ENABLED.getAsBoolean();
            click(widgets(IsoToggle.class).get(0), 0);
            check("toggle click changes the setting immediately", IsoMapConfig.ENABLED.getAsBoolean() != value);
            click(widgets(IsoToggle.class).get(0), 0);

            SettingsScreen.selectTab(SettingsScreen.Tab.MINIMAP);
            mc.setScreen(new SettingsScreen(null));
            IsoSlider size = widgets(IsoSlider.class).get(0);
            mc.screen.mouseClicked(size.getX() + size.getWidth() * 0.9, size.getY() + 4, 0);
            mc.screen.mouseReleased(size.getX() + size.getWidth() * 0.9, size.getY() + 4, 0);
            check("slider click changes the setting", IsoMapConfig.SIZE.getAsInt() > 250);
            widgets(IsoButton.class).stream().filter(b -> b.getMessage().getString().equals(Component.translatable("isomap.settings.reset_tab").getString())).findFirst().ifPresent(b -> click(b, 0));
            check("reset tab restores the default", IsoMapConfig.SIZE.getAsInt() == IsoMapConfig.SIZE.getDefault());

            SettingsScreen.selectTab(SettingsScreen.Tab.CONTROLS);
            mc.setScreen(new SettingsScreen(null));
        });
        run("key rebinding", () -> {
            AbstractWidget keyButton = children().stream().filter(c -> c.getClass().getSimpleName().equals("KeyButton")).map(AbstractWidget.class::cast).findFirst().orElseThrow();
            click(keyButton, 0);
            mc.screen.keyPressed(GLFW.GLFW_KEY_F7, GLFW.glfwGetKeyScancode(GLFW.GLFW_KEY_F7), 0);
            check("a key can be rebound in game", KeyBindings.OPEN_MAP.getKey().equals(InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_F7)));
            widgets(IsoButton.class).stream().filter(b -> b.getMessage().getString().equals(Component.translatable("isomap.settings.reset_tab").getString())).findFirst().ifPresent(b -> click(b, 0));
            check("reset tab restores the keys", KeyBindings.OPEN_MAP.isDefault());
        });
        run("close settings", () -> {
            SettingsScreen.selectTab(SettingsScreen.Tab.GENERAL);
            mc.setScreen(new PinnedPointerSettings());
        });
        shot("11_settings_hover");
        run("close", () -> mc.setScreen(null));

        // ---- Config file
        run("config file", () -> {
            IsoMapConfig.SIZE.set(140);
            IsoMapConfig.save();
            // The regression this guards: a value changed right after a save used to revert a moment later.
            IsoMapConfig.SIZE.set(200);
        });
        sleep(60);
        run("config file check", () -> {
            check("a value changed right after a save does not revert (" + IsoMapConfig.SIZE.getAsInt() + ")", IsoMapConfig.SIZE.getAsInt() == 200);
            IsoMapConfig.SIZE.set(140);
            IsoMapConfig.save();
            try {
                String json = Files.readString(IsoMapConfig.file());
                check("settings reach the config file", json.contains("\"minimap.size\": 140"));
                IsoMapConfig.SIZE.set(150);
                IsoMapConfig.load();
                check("settings come back from the file", IsoMapConfig.SIZE.getAsInt() == 140);

                Files.writeString(IsoMapConfig.file(), "{ \"minimap.size\": 99999, \"minimap.anchor\": \"NOPE\", \"general.enabled\": 7 }");
                IsoMapConfig.load();
                check("out of range values are clamped", IsoMapConfig.SIZE.getAsInt() == 320);
                check("unknown enum values fall back to the default", IsoMapConfig.ANCHOR.get() == IsoMapConfig.ANCHOR.getDefault());
                check("wrongly typed values fall back to the default", IsoMapConfig.ENABLED.getAsBoolean());

                Files.writeString(IsoMapConfig.file(), "{ \"camera.mode\": \"CAMERA_UP\", \"state.yawOffset\": -170.0 }");
                IsoMapConfig.load();
                check("the offset left by the removed camera mode is dropped", IsoMapConfig.STATE_YAW.get() == 0.0);

                Files.writeString(IsoMapConfig.file(), "{ this is not json");
                IsoMapConfig.load();
                check("a damaged settings file means defaults", IsoMapConfig.SIZE.getAsInt() == IsoMapConfig.SIZE.getDefault());
                check("a damaged settings file is set aside", !Files.exists(IsoMapConfig.file()));
            } catch (IOException e) {
                check("config file readable: " + e, false);
            }
            IsoMapConfig.SIZE.set(IsoMapConfig.SIZE.getDefault());
            IsoMapConfig.ENABLED.set(true);
            IsoMapConfig.save();
        });

        // ---- Waypoint screens
        run("list screen", () -> mc.setScreen(new WaypointListScreen(null)));
        shot("12_waypoint_list");
        run("tag filter click", () -> {
            int total = countRows();
            TagChip chip = widgets(TagChip.class).stream().filter(c -> c.getMessage().getString().equals("#explore")).findFirst().orElseThrow();
            click(chip, 0);
            mc.tell(() -> check("tag chip filters the list (" + total + " -> " + countRows() + ")", countRows() == 1));
        });
        shot("13_waypoint_list_filtered");
        run("new waypoint screen", () -> mc.setScreen(WaypointEditScreen.forNewAtPlayer(new WaypointListScreen(null))));
        shot("14_waypoint_new");
        run("edit waypoint screen", () -> mc.setScreen(WaypointEditScreen.forExisting(WaypointStore.get().all().get(0), null)));
        shot("15_waypoint_edit");
        run("close", () -> mc.setScreen(null));

        // ---- Full map
        run("map screen", () -> mc.setScreen(new MapScreen(null)));
        sleep(20);
        shot("16_map_screen");
        run("map screen interaction", () -> {
            Screen screen = mc.screen;
            double cx = screen.width / 2.0;
            double cy = screen.height / 2.0;
            double yaw = CameraAngles.targetYawOffset();
            screen.mouseClicked(cx, cy, 0);
            screen.mouseDragged(cx + 60, cy + 20, 0, 60.0, 20.0);
            screen.mouseReleased(cx + 60, cy + 20, 0);
            check("dragging on the map screen turns the camera", Math.abs(CameraAngles.targetYawOffset() - yaw) > 1.0);
            screen.mouseClicked(cx, cy, 1);
            screen.mouseDragged(cx - 80, cy, 1, -80.0, 0.0);
            screen.mouseReleased(cx - 80, cy, 1);
            screen.mouseScrolled(cx, cy, 0.0, -3.0);
        });
        sleep(20);
        shot("17_map_screen_moved");
        run("map reopen cycle", () -> {
            int buffers = MapRuntime.terrain().gpuBufferCount();
            for (int i = 0; i < 20; i++) {
                mc.setScreen(new MapScreen(null));
                mc.setScreen(null);
            }
            check("opening the map 20 times keeps the tile count (" + buffers + ")", MapRuntime.terrain().gpuBufferCount() == buffers);
        });
        run("camera reset", () -> {
            CameraAngles.reset();
            mc.setScreen(null);
        });

        // ---- Frame cost
        sleep(40);
        run("frames with map", SmokeTest::beginSampling);
        sleepFrames();
        double[] frameMs = new double[2];
        run("frames with map end", () -> {
            frameMs[0] = endSampling();
            IsoMapConfig.ENABLED.set(false);
        });
        sleep(40);
        run("frames without map", SmokeTest::beginSampling);
        sleepFrames();
        run("frames without map end", () -> {
            frameMs[1] = endSampling();
            IsoMapConfig.ENABLED.set(true);
            log(String.format(Locale.ROOT, "frame time: with map %.2f ms (%.0f fps), without %.2f ms (%.0f fps), cost %.2f ms",
                    frameMs[0], 1000.0 / frameMs[0], frameMs[1], 1000.0 / frameMs[1], frameMs[0] - frameMs[1]));
            check("map adds under 1.5 ms per frame", frameMs[0] - frameMs[1] < 1.5);
        });

        // ---- Death markers
        run("death 1", () -> {
            IsoMapConfig.DEATH_CHAT.set(true);
            playerCommand("kill @s");
        });
        waitFor("death screen", () -> mc.screen instanceof DeathScreen, 20 * 10);
        sleep(10);
        run("death check", () -> {
            long deaths = WaypointStore.get().all().stream().filter(w -> w.death).count();
            check("death marker created", deaths == 1);
            Waypoint death = WaypointStore.get().all().stream().filter(w -> w.death).findFirst().orElse(null);
            check("death marker keeps the dimension", death != null && death.dimension.equals("minecraft:overworld"));
            check("death marker carries the tag", death != null && death.tags.contains(Waypoint.DEATH_TAG));
            log("death marker: " + (death == null ? "none" : death.x + " " + death.y + " " + death.z));
        });
        shot("18_death_screen");
        run("respawn", () -> {
            mc.player.respawn();
            mc.setScreen(null);
        });
        waitFor("alive", () -> mc.player != null && mc.player.isAlive() && mc.screen == null, 20 * 15);
        sleep(30);
        run("one death one marker", () -> check("respawn does not duplicate the marker", WaypointStore.get().all().stream().filter(w -> w.death).count() == 1));
        run("death off", () -> {
            IsoMapConfig.DEATH_ENABLED.set(false);
            playerCommand("tp @s ~100 ~ ~");
        });
        sleep(10);
        run("death off kill", () -> playerCommand("kill @s"));
        waitFor("death screen 2", () -> mc.screen instanceof DeathScreen, 20 * 10);
        sleep(10);
        run("death off check", () -> {
            check("disabled death markers create nothing", WaypointStore.get().all().stream().filter(w -> w.death).count() == 1);
            mc.player.respawn();
            mc.setScreen(null);
            IsoMapConfig.DEATH_ENABLED.set(true);
        });
        waitFor("alive 2", () -> mc.player != null && mc.player.isAlive() && mc.screen == null, 20 * 15);
        sleep(30);
        run("immediate respawn", () -> {
            check("re-enabling does not resurrect the skipped death", WaypointStore.get().all().stream().filter(w -> w.death).count() == 1);
            command("gamerule doImmediateRespawn true");
            playerCommand("tp @s ~300 ~ ~");
        });
        sleep(40);
        run("immediate respawn kill", () -> playerCommand("kill @s"));
        sleep(60);
        run("immediate respawn check", () -> {
            long deaths = WaypointStore.get().all().stream().filter(w -> w.death).count();
            check("doImmediateRespawn still records exactly one marker (" + deaths + ")", deaths == 2);
            command("gamerule doImmediateRespawn false");
        });
        run("death limit", () -> {
            WaypointStore store = WaypointStore.get();
            for (int i = 0; i < 5; i++) {
                Waypoint w = make("old death " + i, i, i, 0xE5655C, Waypoint.DEATH_TAG);
                w.death = true;
                w.createdAt = i + 1;
                store.add(w);
            }
            store.trimDeathMarkers(3);
            check("oldest death markers are trimmed", store.all().stream().filter(w -> w.death).count() == 3
                    && store.all().stream().noneMatch(w -> w.name.equals("old death 0")));
            store.all().stream().filter(w -> w.name.startsWith("old death")).toList().forEach(store::remove);
        });

        // ---- Other dimensions and caves
        run("nether", () -> playerCommand("execute in minecraft:the_nether run tp @s 0 64 0"));
        waitFor("in nether", () -> mc.level != null && mc.level.dimension().location().getPath().equals("the_nether") && mc.player != null, 20 * 30);
        waitFor("nether terrain", () -> MapRuntime.terrain().gpuBufferCount() > 4, 20 * 60);
        sleep(60);
        shot("19_hud_nether");
        run("end", () -> playerCommand("execute in minecraft:the_end run tp @s 100 80 0"));
        waitFor("in end", () -> mc.level != null && mc.level.dimension().location().getPath().equals("the_end") && mc.player != null, 20 * 30);
        sleep(100);
        shot("20_hud_end");
        run("overworld underground", () -> playerCommand("execute in minecraft:overworld run tp @s 0 20 0"));
        waitFor("in overworld", () -> mc.level != null && mc.level.dimension().location().getPath().equals("overworld") && mc.player != null, 20 * 30);
        waitFor("terrain again", () -> MapRuntime.terrain().gpuBufferCount() > 4, 20 * 60);
        run("spectate underground", () -> {
            playerCommand("gamemode spectator");
            playerCommand("tp @s 0 20 0");
        });
        sleep(100);
        shot("21_hud_cave");
        run("surface again", () -> {
            playerCommand("gamemode creative");
            playerCommand("tp @s 0 120 0");
        });
        sleep(60);

        // ---- Deep below the surface: the map must stay glued to the marker while the camera turns.
        // A shaft open to the sky keeps sky light at the bottom, the harder case for telling "underground" apart.
        run("shaft", () -> {
            command("fill 0 -50 0 0 200 0 air");
            playerCommand("tp @s 0.5 -49 0.5");
        });
        sleep(120);
        run("shaft slices", () -> check("an open shaft counts as underground", MapRuntime.terrain().isSlicing()));
        measureTurning("shaft");
        // With cave mode off the surface stays on the map, 100 blocks above the player: the hardest case for the camera.
        run("cave mode off", () -> IsoMapConfig.CAVE_MODE.set(dev.isomap.config.CaveMode.OFF));
        sleep(120);
        run("surface shown", () -> check("cave mode off keeps the surface on the map", !MapRuntime.terrain().isSlicing()));
        measureTurning("surface_from_below");
        run("cave mode auto, flying", () -> {
            IsoMapConfig.CAVE_MODE.set(dev.isomap.config.CaveMode.AUTO);
            playerCommand("gamemode spectator");
            playerCommand("tp @s 0.5 220 0.5");
        });
        sleep(120);
        measureTurning("flying");
        run("leave shaft", () -> {
            mc.player.setYRot(0f);
            playerCommand("gamemode creative");
            playerCommand("tp @s 0.5 120 0.5");
        });
        sleep(80);

        planLayers();
        planWalkSmoothness();
        planTeleport();
        planMapMenu();
        planPings();
        planCaveViews();
        run("back from caves", () -> {
            playerCommand("gamemode creative");
            playerCommand("tp @s 0.5 120 0.5");
        });
        sleep(80);

        // ---- GUI scale
        run("gui scale 4", () -> {
            mc.options.guiScale().set(4);
            mc.resizeDisplay();
            SettingsScreen.selectTab(SettingsScreen.Tab.MINIMAP);
            mc.setScreen(new SettingsScreen(null));
        });
        shot("22_guiscale4_settings");
        run("edit at gui scale 4", () -> mc.setScreen(WaypointEditScreen.forExisting(WaypointStore.get().all().get(0), null)));
        shot("23_guiscale4_edit");
        run("list at gui scale 4", () -> mc.setScreen(new WaypointListScreen(null)));
        shot("24_guiscale4_list");
        run("hud at gui scale 4", () -> mc.setScreen(null));
        shot("25_guiscale4_hud");
        run("gui scale 2", () -> {
            mc.options.guiScale().set(2);
            mc.resizeDisplay();
        });
        sleep(10);

        // ---- Leaving and rejoining: all GPU data freed, waypoints still there
        int[] savedCount = new int[1];
        run("leave", () -> {
            savedCount[0] = WaypointStore.get().all().size();
            mc.tell(() -> {
                mc.level.disconnect();
                mc.disconnect(new GenericMessageScreen(Component.literal("saving")));
                mc.setScreen(new TitleScreen());
            });
        });
        waitFor("left", () -> mc.level == null && mc.screen instanceof TitleScreen, 20 * 60);
        sleep(10);
        run("state after leaving", () -> {
            check("all tiles freed after leaving the world", MapRuntime.terrain().gpuBufferCount() == 0);
            check("terrain forgotten after leaving the world", MapRuntime.terrain().snapshotCount() == 0);
            check("waypoint store closed", !WaypointStore.get().isOpen());
            mc.createWorldOpenFlows().openWorld(WORLD, () -> check("world reopened", false));
        });
        waitFor("back in world", () -> mc.player != null && mc.level != null && mc.screen == null, 20 * 120);
        waitFor("terrain after rejoin", () -> MapRuntime.terrain().gpuBufferCount() > 4, 20 * 60);
        run("state after rejoining", () -> check("waypoints are back after rejoining (" + savedCount[0] + ")", WaypointStore.get().all().size() == savedCount[0]));
        shot("26_hud_after_rejoin");

        // ---- Long run: memory must settle while the player moves around
        long[] heap = new long[1];
        run("memory start", () -> {
            System.gc();
            heap[0] = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
            playerCommand("gamemode spectator");
        });
        for (int i = 1; i <= 6; i++) {
            int distance = i * 256;
            run("walk " + i, () -> playerCommand("tp @s " + distance + " 120 " + distance));
            sleep(60);
        }
        run("memory end", () -> {
            System.gc();
            long now = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
            log(String.format(Locale.ROOT, "heap: %.0f MB -> %.0f MB, chunks=%d gpuBuffers=%d",
                    heap[0] / 1.0e6, now / 1.0e6, MapRuntime.terrain().snapshotCount(), MapRuntime.terrain().gpuBufferCount()));
            check("old tiles are evicted while travelling", MapRuntime.terrain().gpuBufferCount() < 100);
            playerCommand("gamemode creative");
        });
        planExploredMap();
        // Exiting while the server still generates chunks for the last teleport can stall its stop loop, with or without this mod.
        sleep(SETTLE_BEFORE_EXIT_TICKS);
    }

    /** On-screen distance between the player marker and the terrain right next to the player, in GUI units. */
    private static double groundOffset() {
        var player = mc().player;
        var view = MapRuntime.hudView();
        org.joml.Vector3f marker = new org.joml.Vector3f();
        org.joml.Vector3f ground = new org.joml.Vector3f();
        int groundX = player.getBlockX() + 3;
        dev.isomap.client.render.MarkerRenderer.projectPlayer(view, player.getX(), player.getY(), player.getZ(), marker);
        view.project(groundX + 0.5, MapRuntime.terrain().heightAt(groundX, player.getBlockZ()) + 1.0, player.getZ(), ground);
        return Math.hypot(ground.x - marker.x, ground.y - marker.y);
    }

    /** How far the player marker is from the centre of the map, in GUI units. */
    private static double markerFromCenter() {
        var player = mc().player;
        org.joml.Vector3f marker = new org.joml.Vector3f();
        dev.isomap.client.render.MarkerRenderer.projectPlayer(MapRuntime.hudView(), player.getX(), player.getY(), player.getZ(), marker);
        return Math.hypot(marker.x, marker.y);
    }

    /** The map must follow a turning camera at frame rate whatever the update rate says; at rest that rate applies. */
    private static void planTurningSmoothness() {
        Minecraft mc = mc();
        long[] mark = new long[1];
        double[] restFrame = new double[1];
        run("turning setup", () -> {
            IsoMapConfig.UPDATE_FPS.set(5);
        });
        sleep(100);
        run("rest start", () -> {
            mark[0] = MapRuntime.hudView().renderCount();
            beginSampling();
        });
        sleep(100);
        run("rest end", () -> {
            double perSecond = (MapRuntime.hudView().renderCount() - mark[0]) / 5.0;
            restFrame[0] = endSampling();
            log(String.format(Locale.ROOT, "map at rest: %.1f images/s, frame %.2f ms", perSecond, restFrame[0]));
            check("at rest the update rate setting applies (" + Math.round(perSecond) + ")", perSecond <= 7.0);
            mark[0] = MapRuntime.hudView().renderCount();
            turning = true;
            beginSampling();
        });
        sleep(100);
        run("turn end", () -> {
            double perSecond = (MapRuntime.hudView().renderCount() - mark[0]) / 5.0;
            double frame = endSampling();
            turning = false;
            CameraAngles.reset();
            log(String.format(Locale.ROOT, "map turning: %.1f images/s, frame %.2f ms (%.0f fps)", perSecond, frame, 1000.0 / frame));
            check("a turning camera is not held back by the update rate (" + Math.round(perSecond) + ")", perSecond >= 60.0);
            IsoMapConfig.UPDATE_FPS.set(IsoMapConfig.UPDATE_FPS.getDefault());
            mc.player.setYRot(0f);
        });
        sleep(40);
    }

    private static java.util.Optional<dev.isomap.client.ping.Ping> pingFrom(String name) {
        return dev.isomap.client.ping.PingManager.active().stream().filter(p -> p.senderName.equals(name)).findFirst();
    }

    /** Lines the mod recognised as pings and hid from chat. */
    @SubscribeEvent(priority = net.neoforged.bus.api.EventPriority.LOWEST, receiveCanceled = true)
    public static void onChatSeen(net.neoforged.neoforge.client.event.ClientChatReceivedEvent event) {
        if (ACTIVE && event.isCanceled() && event.getMessage().getString().contains("[IsoMap] ping")) {
            hiddenChatPings++;
        }
    }

    /** Floating blocks must not grow pillars, crowns must have air under them, and what that costs is written down. */
    private static void planLayers() {
        Minecraft mc = mc();
        int[] place = new int[3];
        long[] costOff = new long[2];
        run("layers setup", () -> {
            playerCommand("gamemode spectator");
            playerCommand("tp @s 0.5 110 0.5");
            MapRuntime.hudView().setZoomTarget(4.0);
            CameraAngles.reset();
            // A low camera, from where the space under things can be seen.
            CameraAngles.addMouseInput(0.0, -80.0);
        });
        sleep(60);
        run("floating log", () -> {
            int x = mc.player.getBlockX() + 3;
            int z = mc.player.getBlockZ() + 3;
            // Not WORLD_SURFACE: grass and flowers are no ground, neither for the map nor for this check.
            int ground = mc.level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, x, z) - 1;
            place[0] = x;
            place[1] = ground;
            place[2] = z;
            playerCommand("setblock " + x + " " + (ground + 8) + " " + z + " minecraft:oak_log");
        });
        waitFor("log on the map", () -> MapRuntime.terrain().heightAt(place[0], place[2]) == place[1] + 8, 20 * 15);
        sleep(40);
        run("floating log check", () -> {
            int layers = MapRuntime.terrain().layersAt(place[0], place[2]);
            int ground = MapRuntime.terrain().groundAt(place[0], place[2]);
            check("a floating block is its own layer (" + layers + ")", layers == 2);
            check("the ground under a floating block stays where it is (" + ground + " / " + place[1] + ")", ground == place[1]);
        });
        shot("36_floating_block");
        run("no floating log", () -> playerCommand("setblock " + place[0] + " " + (place[1] + 8) + " " + place[2] + " minecraft:air"));
        waitFor("log gone", () -> MapRuntime.terrain().layersAt(place[0], place[2]) == 1, 20 * 15);
        run("log gone check", () -> check("a removed block leaves one layer", MapRuntime.terrain().layersAt(place[0], place[2]) == 1));
        run("crowns", () -> {
            int leaves = 0;
            int withGround = 0;
            int px = mc.player.getBlockX();
            int pz = mc.player.getBlockZ();
            for (int dz = -60; dz <= 60; dz++) {
                for (int dx = -60; dx <= 60; dx++) {
                    int x = px + dx;
                    int z = pz + dz;
                    int top = mc.level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, x, z) - 1;
                    if (mc.level.getBlockState(new net.minecraft.core.BlockPos(x, top, z)).is(net.minecraft.tags.BlockTags.LEAVES) && MapRuntime.terrain().heightAt(x, z) == top) {
                        leaves++;
                        if (MapRuntime.terrain().layersAt(x, z) >= 2 && MapRuntime.terrain().groundAt(x, z) < top) {
                            withGround++;
                        }
                    }
                }
            }
            log("crowns: " + leaves + " leaf columns, " + withGround + " with separate ground");
            check("tree crowns are found in this world (" + leaves + ")", leaves > 100);
            check("crowns stand apart from the ground (" + withGround + "/" + leaves + ")", withGround >= leaves * 0.95);
        });
        shot("37_tree_crowns");
        // The price: the same place with the setting off and on, after every chunk has been re-read and re-meshed.
        run("layers off", () -> {
            MapRuntime.terrain().takeAverageSampleNanos();
            IsoMapConfig.TERRAIN_LAYERS.set(false);
        });
        sleep(200);
        run("cost off", () -> {
            costOff[0] = MapRuntime.terrain().takeAverageSampleNanos();
            costOff[1] = MapRuntime.terrain().meshVertexCount();
            check("the setting turns layers off", MapRuntime.terrain().layersAt(place[0], place[2]) == 1);
        });
        shot("38_layers_off");
        run("layers on", () -> IsoMapConfig.TERRAIN_LAYERS.set(true));
        sleep(200);
        run("cost on", () -> {
            long nanos = MapRuntime.terrain().takeAverageSampleNanos();
            long vertices = MapRuntime.terrain().meshVertexCount();
            log(String.format(Locale.ROOT, "layers cost: chunk read %.0f us -> %.0f us, map vertices %d -> %d (%.2fx)",
                    costOff[0] / 1000.0, nanos / 1000.0, costOff[1], vertices, vertices / (double) Math.max(1L, costOff[1])));
            check("layers at most triple the map's vertices", vertices <= costOff[1] * 3);
            MapRuntime.hudView().setZoomTarget(IsoMapConfig.STATE_ZOOM.getDefault());
            CameraAngles.reset();
        });
    }

    /** A player walking across uneven land: the tilted map must not jump up and down, and must slide in whole pixels. */
    private static void planWalkSmoothness() {
        Minecraft mc = mc();
        double[] last = {Double.NaN, Double.NaN, Double.NaN};
        double[] first = new double[1];
        double[] worst = new double[2];
        int[] counts = new int[4];
        org.joml.Vector3f anchor = new org.joml.Vector3f();
        run("walk setup", () -> {
            playerCommand("gamemode spectator");
            playerCommand("tp @s 0.5 110 0.5");
            CameraAngles.reset();
        });
        sleep(80);
        for (int i = 0; i < 240; i++) {
            run("walk", () -> {
                var player = mc.player;
                player.setPos(player.getX() + 0.25, player.getY(), player.getZ() + 0.1);
                var view = MapRuntime.hudView();
                int ground = MapRuntime.terrain().groundLevel(player.getBlockX(), player.getBlockZ(), 64);
                if (!Double.isNaN(last[0])) {
                    worst[0] = Math.max(worst[0], Math.abs(view.focusY() - last[0]));
                    counts[0] += ground != (int) last[2] ? 1 : 0;
                }
                last[0] = view.focusY();
                last[2] = ground;
                // A fixed point of the world must always be a whole number of image pixels from where it first was.
                view.project(0.5, 64.0, 0.5, anchor);
                double px = anchor.x * view.pixelsPerGuiUnit();
                double py = anchor.y * view.pixelsPerGuiUnit();
                if (!view.pixelSnapped()) {
                    // The camera moved: the grid itself changes, so the comparison starts over once it is still again.
                    counts[1]++;
                    counts[2] += CameraAngles.isMoving() ? 1 : 0;
                    counts[3] += MapRuntime.terrain().isSlicing() ? 1 : 0;
                    last[1] = Double.NaN;
                    return;
                }
                if (Double.isNaN(last[1])) {
                    last[1] = px;
                    first[0] = py;
                }
                double dx = px - last[1];
                double dy = py - first[0];
                worst[1] = Math.max(worst[1], Math.max(Math.abs(dx - Math.rint(dx)), Math.abs(dy - Math.rint(dy))));
            });
        }
        run("walk check", () -> {
            log(String.format(Locale.ROOT, "walk: ground level changed %d times, biggest focus height change per tick %.3f, off the pixel grid by %.3f; "
                    + "camera not still in %d of 240 ticks (turning %d, cave view %d)", counts[0], worst[0], worst[1], counts[1], counts[2], counts[3]));
            check("the camera stays still while only walking (" + counts[1] + ")", counts[1] <= 24);
            check("the camera height glides while walking (" + String.format(Locale.ROOT, "%.3f", worst[0]) + ")", worst[0] < 0.4);
            check("the terrain slides in whole pixels (" + String.format(Locale.ROOT, "%.3f", worst[1]) + ")", worst[1] < 0.05);
            playerCommand("gamemode creative");
            playerCommand("tp @s 0.5 120 0.5");
        });
        sleep(60);
    }

    private static void face(net.minecraft.world.entity.player.Player player, double x, double y, double z) {
        double dx = x - player.getX();
        double dy = y - player.getEyeY();
        double dz = z - player.getZ();
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz)));
        player.setYRot(yaw);
        player.setXRot(pitch);
        player.yRotO = yaw;
        player.xRotO = pitch;
    }

    /** One client alone: placing, following an entity, receiving through both channels, the setting, and expiry. */
    private static void planPings() {
        Minecraft mc = mc();
        int[] counts = new int[2];
        run("ping setup", () -> {
            dev.isomap.client.ping.PingManager.clear();
            IsoMapConfig.PING_SECONDS.set(30);
            playerCommand("gamemode creative");
            mc.player.setXRot(25f);
            counts[0] = dev.isomap.ping.PingNetwork.receivedCount();
            net.minecraft.client.KeyMapping.click(KeyBindings.PING.getKey());
        });
        sleep(10);
        run("ping placed", () -> {
            check("the ping key places a ping", pingFrom(mc.player.getGameProfile().getName()).isPresent());
            check("the ping reached the server", dev.isomap.ping.PingNetwork.receivedCount() > counts[0]);
        });
        sleep(10);
        run("ping far away", () -> {
            double x = mc.player.getX() + 8;
            double z = mc.player.getZ() + 28;
            int ground = mc.level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, (int) Math.floor(x), (int) Math.floor(z));
            dev.isomap.client.ping.PingClient.pingAt(mc, x, ground, z);
            face(mc.player, x, ground + 3.0, z);
        });
        sleep(20);
        shot("33_ping_self");
        run("ping off", () -> {
            counts[1] = dev.isomap.client.ping.PingManager.active().size();
            IsoMapConfig.PINGS_ENABLED.set(false);
            net.minecraft.client.KeyMapping.click(KeyBindings.PING.getKey());
        });
        sleep(10);
        run("ping off check", () -> {
            check("with pings off the key does nothing", dev.isomap.client.ping.PingManager.active().size() == counts[1]);
            IsoMapConfig.PINGS_ENABLED.set(true);
            var player = mc.player;
            playerCommand("summon minecraft:pig " + (player.getX() + 6) + " " + player.getY() + " " + player.getZ() + " {NoAI:1b,Tags:[\"isoping\"]}");
            player.setYRot(-90f);
            player.setXRot(10f);
            player.yRotO = -90f;
            player.xRotO = 10f;
        });
        sleep(20);
        double[] pigX = new double[1];
        run("ping pig", () -> {
            dev.isomap.client.ping.PingManager.clear();
            dev.isomap.client.ping.PingClient.pingLookedAt(mc);
            var ping = pingFrom(mc.player.getGameProfile().getName());
            check("looking at a mob pings the mob", ping.isPresent() && ping.get().entityId != dev.isomap.ping.PingPayload.NO_ENTITY);
            pigX[0] = ping.map(p -> p.x).orElse(0.0);
            // Relative coordinates count from the player who runs the command, and the pig already stands at +6.
            playerCommand("tp @e[tag=isoping] ~12 ~ ~");
        });
        sleep(20);
        run("ping follows", () -> {
            double x = pingFrom(mc.player.getGameProfile().getName()).map(p -> p.x).orElse(0.0);
            check("the ping follows the mob (" + Math.round(x - pigX[0]) + ")", Math.abs(x - pigX[0] - 6.0) < 1.0);
            playerCommand("kill @e[tag=isoping]");
            var server = mc.getSingleplayerServer();
            double px = mc.player.getX() + 9;
            double py = mc.player.getY();
            double pz = mc.player.getZ() + 4;
            server.execute(() -> net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(server.getPlayerList().getPlayers().get(0),
                    new dev.isomap.ping.PingPayload(new java.util.UUID(1L, 2L), "Friend", net.minecraft.resources.ResourceLocation.parse("minecraft:overworld"), px, py, pz, dev.isomap.ping.PingPayload.NO_ENTITY)));
        });
        waitFor("relayed ping", () -> pingFrom("Friend").isPresent(), 20 * 5);
        run("relayed check", () -> {
            check("a ping from the server appears", pingFrom("Friend").isPresent());
            counts[0] = hiddenChatPings;
            command("tellraw @a {\"text\":\"[IsoMap] ping 5 70 5 minecraft:overworld\"}");
        });
        waitFor("chat ping", () -> hiddenChatPings > counts[0], 20 * 5);
        run("chat check", () -> {
            check("a chat ping is hidden from chat", hiddenChatPings > counts[0]);
            check("a chat ping appears at its place", dev.isomap.client.ping.PingManager.active().stream().anyMatch(p -> p.x == 5.5 && p.z == 5.5));
            IsoMapConfig.PING_SECONDS.set(5);
        });
        sleep(120);
        run("expiry", () -> {
            check("pings disappear by themselves", dev.isomap.client.ping.PingManager.active().isEmpty());
            IsoMapConfig.PING_SECONDS.set(IsoMapConfig.PING_SECONDS.getDefault());
        });
    }

    /** First of two real clients: opens the world on the network and expects pings from the second one. */
    private static void planHost() {
        Minecraft mc = mc();
        planFreshWorld();
        run("open to lan", () -> {
            var server = mc.getSingleplayerServer();
            server.setUsesAuthentication(false);
            check("world opened on the network", server.publishServer(GameType.CREATIVE, true, TWO_CLIENT_PORT));
            log("host ready");
            command("time set noon");
            command("gamerule doMobSpawning false");
        });
        waitFor("guest joins", () -> mc.getSingleplayerServer().getPlayerCount() >= 2, 20 * 200);
        run("guest joined", () -> check("second client joined", mc.getSingleplayerServer().getPlayerCount() >= 2));
        waitFor("ping from guest", () -> pingFrom(GUEST_NAME).isPresent(), 20 * 60);
        run("guest ping", () -> {
            var ping = pingFrom(GUEST_NAME);
            check("a ping from the other player arrives through the server", ping.isPresent());
            check("the server stamps the sender's name", ping.isPresent() && !ping.get().sender.equals(mc.player.getUUID()));
            mc.player.setXRot(18f);
            dev.isomap.client.ping.PingClient.pingLookedAt(mc);
            mc.player.setXRot(6f);
        });
        shot("32_ping_two_players");
        run("map with pings", () -> mc.setScreen(new MapScreen(null)));
        sleep(20);
        shot("34_ping_on_map");
        run("close map", () -> mc.setScreen(null));
        waitFor("chat ping from guest", () -> dev.isomap.client.ping.PingManager.active().stream().anyMatch(p -> p.x == 1111.5), 20 * 60);
        run("guest chat ping", () -> {
            var ping = dev.isomap.client.ping.PingManager.active().stream().filter(p -> p.x == 1111.5).findFirst();
            check("a chat ping from the other player arrives", ping.isPresent());
            check("the chat ping knows who sent it", ping.isPresent() && ping.get().senderName.equals(GUEST_NAME));
            check("the chat line was hidden", hiddenChatPings > 0);
        });
        waitFor("guest leaves", () -> mc.getSingleplayerServer().getPlayerCount() <= 1, 20 * 120);
        sleep(SETTLE_BEFORE_EXIT_TICKS / 3);
    }

    private static boolean listed(String name) {
        var connection = mc().getConnection();
        return connection != null && connection.getPlayerInfo(name) != null;
    }

    /**
     * One client on a real dedicated server. ISOMAP_PEER names another client with the mod to exchange pings with,
     * ISOMAP_PLAIN a player without the mod who must stay connected while pings go around.
     */
    private static void planDedicated() {
        Minecraft mc = mc();
        String peer = System.getenv().getOrDefault("ISOMAP_PEER", "");
        String plain = System.getenv().getOrDefault("ISOMAP_PLAIN", "");
        int[] attempt = new int[1];
        int[] seen = new int[1];
        run("dedicated setup", () -> {
            mc.options.pauseOnLostFocus = false;
            mc.options.onboardAccessibility = false;
            IsoMapConfig.resetAll();
        });
        waitFor("connected", () -> {
            if (mc.player != null && mc.level != null && mc.screen == null) {
                return true;
            }
            if (attempt[0]++ % 200 == 0 && (mc.screen instanceof TitleScreen || mc.screen instanceof net.minecraft.client.gui.screens.DisconnectedScreen)) {
                String address = "localhost:" + TWO_CLIENT_PORT;
                net.minecraft.client.gui.screens.ConnectScreen.startConnecting(new TitleScreen(), mc,
                        net.minecraft.client.multiplayer.resolver.ServerAddress.parseString(address),
                        new net.minecraft.client.multiplayer.ServerData("dedicated", address, net.minecraft.client.multiplayer.ServerData.Type.OTHER), false, null);
            }
            return false;
        }, 20 * 200);
        sleep(60);
        run("on the server", () -> {
            check("this is a dedicated server, not a local world", mc.getSingleplayerServer() == null);
            check("the dedicated server offers the ping channel", mc.getConnection().hasChannel(dev.isomap.ping.PingPayload.TYPE));
        });
        String me = System.getenv().getOrDefault("ISOMAP_SMOKE_USER", "Dev");
        double mine = me.equals("Dev") ? 1111.0 : 2222.0;
        double theirs = me.equals("Dev") ? 2222.5 : 1111.5;
        if (!plain.isEmpty()) {
            waitFor("player without the mod", () -> listed(plain), 20 * 120);
            run("pings beside a plain client", () -> {
                check("a player without IsoMap can join the server", listed(plain));
                seen[0] = dev.isomap.ping.PingNetwork.receivedCount();
                dev.isomap.client.ping.PingClient.pingAt(mc, mc.player.getX() + 5, mc.player.getY(), mc.player.getZ() + 5);
            });
            sleep(20);
            run("second ping", () -> dev.isomap.client.ping.PingClient.pingAt(mc, mc.player.getX() - 5, mc.player.getY(), mc.player.getZ() + 5));
            sleep(100);
            run("plain still here", () -> check("the player without IsoMap stays connected while pings are sent", listed(plain)));
        }
        if (!peer.isEmpty()) {
            waitFor("peer joins", () -> listed(peer), 20 * 200);
            run("ping peer", () -> dev.isomap.client.ping.PingClient.pingAt(mc, mc.player.getX() + 7, mc.player.getY(), mc.player.getZ() - 9));
            waitFor("ping from peer", () -> pingFrom(peer).isPresent(), 20 * 90);
            run("peer ping", () -> {
                var ping = pingFrom(peer);
                check("the other player's ping arrives through the dedicated server", ping.isPresent());
                check("the dedicated server stamps the sender", ping.isPresent() && !ping.get().sender.equals(mc.player.getUUID()));
                ping.ifPresent(p -> face(mc.player, p.x, p.y + 3.0, p.z));
            });
            sleep(20);
            shot("41_dedicated_" + me);
            run("chat ping", () -> dev.isomap.client.ping.PingClient.sendThroughChat(mc, mine, 70.0, mine));
            waitFor("chat ping from peer", () -> dev.isomap.client.ping.PingManager.active().stream().anyMatch(p -> p.x == theirs), 20 * 90);
            run("peer chat ping", () -> {
                var ping = dev.isomap.client.ping.PingManager.active().stream().filter(p -> p.x == theirs).findFirst();
                check("a chat ping crosses the dedicated server", ping.isPresent());
                check("the chat ping knows its sender", ping.isPresent() && ping.get().senderName.equals(peer));
                check("chat ping lines are hidden", hiddenChatPings > 0);
            });
            // The other client may still be waiting for this one's chat ping.
            sleep(200);
        }
        run("leave server", () -> mc.tell(() -> {
            mc.level.disconnect();
            mc.disconnect(new GenericMessageScreen(Component.literal("leaving")));
            mc.setScreen(new TitleScreen());
        }));
        waitFor("left", () -> mc.level == null, 20 * 30);
        sleep(20);
    }

    /** With other rendering mods installed: the map, the full map and pings must still draw. Screenshots tell the rest. */
    private static void planCompat() {
        Minecraft mc = mc();
        planFreshWorld();
        sleep(100);
        long[] renders = new long[1];
        run("compat hud", () -> {
            check("terrain reaches the GPU next to other rendering mods", MapRuntime.terrain().gpuBufferCount() > 4);
            renders[0] = MapRuntime.hudView().renderCount();
            mc.player.setXRot(20f);
        });
        sleep(40);
        run("compat renders", () -> check("the minimap keeps rendering (" + (MapRuntime.hudView().renderCount() - renders[0]) + ")",
                MapRuntime.hudView().renderCount() > renders[0]));
        shot("c1_hud");
        run("compat ping", () -> dev.isomap.client.ping.PingClient.pingLookedAt(mc));
        sleep(20);
        run("compat ping check", () -> check("a ping can be placed", pingFrom(mc.player.getGameProfile().getName()).isPresent()));
        shot("c2_ping");
        run("compat map", () -> mc.setScreen(new MapScreen(null)));
        sleep(40);
        shot("c3_map");
        run("compat close", () -> mc.setScreen(null));
        sleep(SETTLE_BEFORE_EXIT_TICKS / 3);
    }

    /** Second of two real clients: joins the first one over the network, pings it and expects its ping back. */
    private static void planGuest() {
        Minecraft mc = mc();
        int[] attempt = new int[1];
        run("guest setup", () -> {
            mc.options.pauseOnLostFocus = false;
            mc.options.onboardAccessibility = false;
            IsoMapConfig.resetAll();
        });
        waitFor("connected", () -> {
            if (mc.player != null && mc.level != null && mc.screen == null) {
                return true;
            }
            if (attempt[0]++ % 200 == 0 && (mc.screen instanceof TitleScreen || mc.screen instanceof net.minecraft.client.gui.screens.DisconnectedScreen)) {
                String address = "localhost:" + TWO_CLIENT_PORT;
                net.minecraft.client.gui.screens.ConnectScreen.startConnecting(new TitleScreen(), mc,
                        net.minecraft.client.multiplayer.resolver.ServerAddress.parseString(address),
                        new net.minecraft.client.multiplayer.ServerData("host", address, net.minecraft.client.multiplayer.ServerData.Type.LAN), false, null);
            }
            return false;
        }, 20 * 200);
        sleep(100);
        run("guest pings", () -> {
            check("the server offers the ping channel", mc.getConnection().hasChannel(dev.isomap.ping.PingPayload.TYPE));
            check("this client is the guest", mc.player.getGameProfile().getName().equals(GUEST_NAME));
            dev.isomap.client.ping.PingClient.pingAt(mc, mc.player.getX() + 7, mc.player.getY(), mc.player.getZ() - 9);
        });
        waitFor("ping from host", () -> pingFrom("Dev").isPresent(), 20 * 60);
        run("host ping", () -> {
            check("the other player's ping arrives here too", pingFrom("Dev").isPresent());
            pingFrom("Dev").ifPresent(p -> face(mc.player, p.x, p.y + 3.0, p.z));
        });
        sleep(20);
        shot("35_guest_sees_ping");
        run("guest chat ping", () -> dev.isomap.client.ping.PingClient.sendThroughChat(mc, 1111.0, 70.0, 1111.0));
        waitFor("own chat line back", () -> hiddenChatPings > 0, 20 * 10);
        run("own chat ping", () -> {
            check("the own chat line is hidden", hiddenChatPings > 0);
            check("the own chat line does not make a second ping", dev.isomap.client.ping.PingManager.active().stream().noneMatch(p -> p.x == 1111.5));
        });
        sleep(40);
    }

    /** With 3D off the map is the view from straight above, whatever the camera setting says. */
    private static void planFlatMap() {
        run("3d off", () -> IsoMapConfig.RENDER_3D.set(false));
        sleep(40);
        run("flat check", () -> check("3D off shows the map from straight above", MapRuntime.hudView().pitchDegrees() == 90.0));
        shot("29_flat_map");
        run("open map flat", () -> mc().setScreen(new MapScreen(null)));
        sleep(1);
        run("map opens flat", () -> {
            double pitch = MapRuntime.screenView() == null ? -1.0 : MapRuntime.screenView().pitchDegrees();
            check("a flat map opens flat, without a tilt animation (" + pitch + ")", pitch == 90.0);
            mc().setScreen(null);
        });
        sleep(4);
        run("3d on", () -> IsoMapConfig.RENDER_3D.set(true));
        sleep(40);
        run("tilted check", () -> check("3D on brings the tilt back", MapRuntime.hudView().pitchDegrees() < 89.0));
    }

    /** The teleport button exists only with the right to use it, and takes the player there, across dimensions too. */
    private static void planTeleport() {
        Minecraft mc = mc();
        String label = Component.translatable("isomap.waypoints.teleport").getString();
        java.util.function.Supplier<IsoButton> button = () -> widgets(IsoButton.class).stream()
                .filter(b -> b.getMessage().getString().equals(label)).findFirst().orElse(null);
        Waypoint[] target = new Waypoint[1];
        run("teleport setup", () -> {
            WaypointStore.get().all().stream().filter(w -> w.name.equals("Teleport target")).toList().forEach(WaypointStore.get()::remove);
            target[0] = make("Teleport target", 90, 70, 0x4FD1C5);
            target[0].y = 110;
            WaypointStore.get().add(target[0]);
            playerCommand("gamemode creative");
            mc.player.setPermissionLevel(0);
            mc.setScreen(new WaypointListScreen(null));
        });
        sleep(4);
        run("no rights", () -> {
            check("no teleport button without cheats", button.get() == null);
            mc.player.setPermissionLevel(4);
            mc.setScreen(new WaypointListScreen(null));
        });
        shot("31_teleport_button");
        run("row buttons", () -> {
            // Each control on a row must do its own job, not open the editor like the row around it.
            AbstractWidget visibility = children().stream().filter(c -> c.getClass().getSimpleName().equals("VisibilityButton")).map(AbstractWidget.class::cast)
                    .filter(w -> w.getY() < mc.screen.height).findFirst().orElseThrow();
            long hiddenBefore = WaypointStore.get().all().stream().filter(w -> !w.visible).count();
            click(visibility, 0);
            long hiddenAfter = WaypointStore.get().all().stream().filter(w -> !w.visible).count();
            check("the visibility button on a row works (" + hiddenBefore + " -> " + hiddenAfter + ")", hiddenAfter != hiddenBefore && mc.screen instanceof WaypointListScreen);
            click(visibility, 0);
            Waypoint doomed = make("Doomed", 3, 3, 0x8A9099);
            doomed.createdAt = System.currentTimeMillis() + 60_000L;
            WaypointStore.get().add(doomed);
        });
        sleep(4);
        run("row delete", () -> {
            String cross = "\u00D7";
            IsoButton delete = widgets(IsoButton.class).stream().filter(b -> b.getMessage().getString().equals(cross)).min(Comparator.comparingInt(AbstractWidget::getY)).orElseThrow();
            click(delete, 0);
            check("one click on delete only arms it", WaypointStore.get().all().stream().anyMatch(w -> w.name.equals("Doomed")) && mc.screen instanceof WaypointListScreen);
            click(delete, 0);
            check("the second click deletes the waypoint", WaypointStore.get().all().stream().noneMatch(w -> w.name.equals("Doomed")));
        });
        sleep(4);
        run("press teleport", () -> {
            IsoButton tp = button.get();
            check("teleport button appears with cheats", tp != null);
            if (tp != null) {
                click(tp, 0);
            }
        });
        sleep(30);
        run("teleported", () -> {
            double distance = Math.sqrt(mc.player.distanceToSqr(target[0].x + 0.5, mc.player.getY(), target[0].z + 0.5));
            check("teleport moves the player to the waypoint (" + Math.round(distance) + ")", distance < 3.0);
            check("teleport closes the screen", mc.screen == null);
            target[0].dimension = "minecraft:the_nether";
            target[0].y = 120;
            dev.isomap.client.waypoint.WaypointTeleport.teleport(mc, target[0]);
        });
        waitFor("in the nether", () -> mc.level != null && mc.level.dimension().location().getPath().equals("the_nether"), 20 * 20);
        run("back", () -> {
            check("teleport crosses dimensions", mc.level.dimension().location().getPath().equals("the_nether"));
            playerCommand("execute in minecraft:overworld run tp @s 0 90 0");
            WaypointStore.get().remove(target[0]);
        });
        waitFor("back home", () -> mc.level != null && mc.level.dimension().location().getPath().equals("overworld") && mc.player != null, 20 * 20);
        sleep(60);
    }

    /** A right click on the full map opens a menu for that place; a right drag still only pans. */
    private static void planMapMenu() {
        Minecraft mc = mc();
        String newLabel = Component.translatable("isomap.map.menu.new_waypoint").getString();
        String tpLabel = Component.translatable("isomap.map.menu.teleport").getString();
        java.util.function.Function<String, IsoButton> find = label -> widgets(IsoButton.class).stream()
                .filter(b -> b.getMessage().getString().equals(label)).findFirst().orElse(null);
        double[] at = new double[2];
        BlockPos[] target = new BlockPos[1];
        run("menu setup", () -> {
            playerCommand("gamemode creative");
            mc.player.setPermissionLevel(0);
            mc.setScreen(new MapScreen(null));
        });
        sleep(30);
        run("menu drag", () -> {
            MapScreen screen = (MapScreen) mc.screen;
            at[0] = screen.width / 2.0 + 40;
            at[1] = screen.height / 2.0 + 20;
            screen.mouseClicked(at[0], at[1], 1);
            screen.mouseDragged(at[0] - 30, at[1], 1, -30.0, 0.0);
            screen.mouseReleased(at[0] - 30, at[1], 1);
            check("a right drag does not open the menu", screen.menuTarget() == null);
        });
        sleep(10);
        run("menu without rights", () -> {
            MapScreen screen = (MapScreen) mc.screen;
            screen.mouseClicked(at[0], at[1], 1);
            screen.mouseReleased(at[0], at[1], 1);
            check("a right click opens the menu", screen.menuTarget() != null && find.apply(newLabel) != null);
            check("no teleport in the menu without cheats", find.apply(tpLabel) == null);
        });
        shot("39_map_menu_no_rights");
        run("menu escape", () -> {
            MapScreen screen = (MapScreen) mc.screen;
            screen.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE, 0, 0);
            check("Escape closes only the menu", mc.screen == screen && screen.menuTarget() == null && find.apply(newLabel) == null);
            screen.mouseClicked(at[0], at[1], 1);
            screen.mouseReleased(at[0], at[1], 1);
            target[0] = screen.menuTarget();
            IsoButton add = find.apply(newLabel);
            if (add != null) {
                click(add, 0);
            }
            check("the menu creates a waypoint at that place", mc.screen instanceof WaypointEditScreen);
            mc.player.setPermissionLevel(4);
            mc.setScreen(new MapScreen(null));
        });
        sleep(30);
        run("menu with rights", () -> {
            MapScreen screen = (MapScreen) mc.screen;
            screen.mouseClicked(at[0], at[1], 1);
            screen.mouseReleased(at[0], at[1], 1);
            target[0] = screen.menuTarget();
            check("teleport is in the menu with cheats", find.apply(tpLabel) != null);
        });
        shot("40_map_menu");
        run("menu teleport", () -> {
            IsoButton tp = find.apply(tpLabel);
            if (tp != null) {
                // Not click(): the teleport closes the map, and there is no screen left to release the button on.
                mc.screen.mouseClicked(tp.getX() + tp.getWidth() / 2.0, tp.getY() + tp.getHeight() / 2.0, 0);
            }
        });
        sleep(30);
        run("menu teleported", () -> {
            BlockPos t = target[0];
            double distance = t == null ? 999.0 : Math.sqrt(mc.player.distanceToSqr(t.getX() + 0.5, t.getY() + 1.0, t.getZ() + 0.5));
            check("teleport from the menu lands on that block (" + Math.round(distance) + ")", distance < 1.5);
            check("teleport from the menu closes the map", mc.screen == null);
            playerCommand("tp @s 0.5 120 0.5");
        });
        sleep(40);
    }

    /** Chunks seen once stay on the map: after leaving the world they come back from disk, far from the player. */
    private static int layerSum(int x, int z) {
        int sum = 0;
        for (int dz = 0; dz < 48; dz++) {
            for (int dx = 0; dx < 48; dx++) {
                sum += MapRuntime.terrain().layersAt(x + dx, z + dz);
            }
        }
        return sum;
    }

    private static void planExploredMap() {
        Minecraft mc = mc();
        int farX = 3000;
        int farZ = -2600;
        int[] height = new int[1];
        int[] layerSum = new int[1];
        String[] key = new String[1];
        run("explore far", () -> {
            playerCommand("gamemode spectator");
            playerCommand("tp @s " + farX + " 120 " + farZ);
        });
        sleep(160);
        run("remember far", () -> {
            height[0] = MapRuntime.terrain().heightAt(farX, farZ);
            layerSum[0] = layerSum(farX, farZ);
            key[0] = WaypointStore.worldKey(mc);
            check("far place was seen (" + height[0] + ")", height[0] != dev.isomap.client.map.ChunkSnapshot.NO_DATA);
            playerCommand("tp @s 0 120 0");
        });
        sleep(200);
        run("leave for good", () -> mc.tell(() -> {
            mc.level.disconnect();
            mc.disconnect(new GenericMessageScreen(Component.literal("saving")));
            mc.setScreen(new TitleScreen());
        }));
        waitFor("left", () -> mc.level == null && mc.screen instanceof TitleScreen, 20 * 60);
        sleep(20);
        run("files", () -> {
            Path dir = FMLPaths.GAMEDIR.get().resolve("isomap").resolve("terrain").resolve(key[0]).resolve("minecraft_overworld");
            Path file = dir.resolve("r." + ((farX >> 4) >> 5) + "." + ((farZ >> 4) >> 5) + ".bin");
            check("explored terrain reached the disk", Files.exists(file));
            try (Stream<Path> files = Files.list(dir)) {
                long bytes = 0;
                long count = 0;
                for (Path f : files.toList()) {
                    bytes += Files.size(f);
                    count++;
                }
                log("explored map on disk: " + count + " regions, " + bytes / 1024 + " KB");
            } catch (IOException e) {
                check("explored directory readable: " + e, false);
            }
            mc.createWorldOpenFlows().openWorld(WORLD, () -> check("world reopened", false));
        });
        waitFor("back again", () -> mc.player != null && mc.level != null && mc.screen == null, 20 * 120);
        waitFor("terrain back", () -> MapRuntime.terrain().gpuBufferCount() > 4, 20 * 60);
        run("look far away", () -> MapRuntime.terrain().setScreenView(farX, farZ, 8, 1));
        waitFor("far place back", () -> MapRuntime.terrain().heightAt(farX, farZ) == height[0], 20 * 20);
        run("far check", () -> {
            check("a chunk seen in an earlier session is back on the map", MapRuntime.terrain().heightAt(farX, farZ) == height[0]);
            log("far chunk: height " + MapRuntime.terrain().heightAt(farX, farZ) + ", player at " + mc.player.blockPosition());
            check("its layers came back from the disk too (" + layerSum[0] + ")", layerSum(farX, farZ) == layerSum[0]);
            MapRuntime.terrain().clearScreenView();
            mc.setScreen(new MapScreen(null));
        });
        sleep(10);
        run("zoom out", () -> {
            for (int i = 0; i < 14; i++) {
                mc.screen.mouseScrolled(mc.screen.width / 2.0, mc.screen.height / 2.0, 0.0, -1.0);
            }
        });
        sleep(100);
        shot("30_explored_map");
        run("close map", () -> mc.setScreen(null));
    }

    /** A waypoint keeps its real height on the map; a pin to the ground shows how high it is. */
    private static void planWaypointHeight() {
        Minecraft mc = mc();
        run("height setup", () -> {
            playerCommand("gamemode creative");
            CameraAngles.reset();
        });
        sleep(40);
        run("height check", () -> {
            var player = mc.player;
            int x = player.getBlockX() + 6;
            int z = player.getBlockZ();
            int ground = MapRuntime.terrain().heightAt(x, z) + 1;
            org.joml.Vector3f low = new org.joml.Vector3f();
            org.joml.Vector3f high = new org.joml.Vector3f();
            Waypoint w = new Waypoint();
            w.x = x;
            w.z = z;
            w.y = ground;
            dev.isomap.client.render.MarkerRenderer.projectWaypoint(MapRuntime.hudView(), w, low);
            w.y = ground + 30;
            dev.isomap.client.render.MarkerRenderer.projectWaypoint(MapRuntime.hudView(), w, high);
            double expected = 30.0 * Math.cos(Math.toRadians(MapRuntime.hudView().pitchDegrees())) * MapRuntime.hudView().zoomTarget();
            log(String.format(Locale.ROOT, "waypoint 30 blocks up moves %.1f gui units up (expected %.1f), sideways %.1f", low.y - high.y, expected, Math.abs(low.x - high.x)));
            check("a waypoint keeps its real height", Math.abs((low.y - high.y) - expected) < 2.0 && Math.abs(low.x - high.x) < 0.5);
        });
    }

    /** Real caves near the player, in the look the map gives them. */
    private static void planCaveViews() {
        Minecraft mc = mc();
        int[][] places = {{0, 0}, {320, 40}, {-200, 260}};
        for (int i = 0; i < places.length; i++) {
            int index = i;
            int[] place = places[i];
            run("go to area " + i, () -> {
                playerCommand("gamemode spectator");
                playerCommand("tp @s " + place[0] + " 100 " + place[1]);
            });
            sleep(120);
            BlockPos[] cave = new BlockPos[1];
            run("find cave " + i, () -> {
                cave[0] = findCave(place[0], place[1]);
                log("cave " + index + ": " + cave[0]);
                if (cave[0] != null) {
                    playerCommand("tp @s " + cave[0].getX() + " " + cave[0].getY() + " " + cave[0].getZ());
                }
            });
            sleep(140);
            run("cave state " + i, () -> check("a real cave shows the cave map (" + index + ")", cave[0] == null || MapRuntime.terrain().isSlicing()));
            shot("28_cave_" + i);
            run("cave torch " + i, () -> {
                if (cave[0] != null) {
                    int cx = cave[0].getX();
                    int cz = cave[0].getZ();
                    int y = cave[0].getY();
                    command("fill " + (cx - 3) + " " + (y - 1) + " " + (cz - 3) + " " + (cx + 3) + " " + (y - 1) + " " + (cz + 3) + " minecraft:glowstone replace #minecraft:base_stone_overworld");
                }
            });
            sleep(80);
            shot("28_cave_" + i + "_lit");
        }
    }

    /** The biggest open space in the rock around a point. */
    private static BlockPos findCave(int centerX, int centerZ) {
        var level = mc().level;
        BlockPos best = null;
        int bestScore = 12;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int x = centerX - 72; x <= centerX + 72; x += 6) {
            for (int z = centerZ - 72; z <= centerZ + 72; z += 6) {
                int surface = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE, x, z);
                for (int y = -40; y < surface - 14; y += 3) {
                    int score = 0;
                    for (int dx = -6; dx <= 6; dx += 2) {
                        for (int dz = -6; dz <= 6; dz += 2) {
                            if (level.getBlockState(pos.set(x + dx, y, z + dz)).isAir()) {
                                score++;
                            }
                        }
                    }
                    if (score > bestScore && level.getBlockState(pos.set(x, y, z)).isAir() && !level.getBlockState(pos.set(x, y - 1, z)).isAir()) {
                        bestScore = score;
                        best = new BlockPos(x, y, z);
                    }
                }
            }
        }
        return best;
    }

    /** Turns the camera through a full circle, checking at each step that the map stays glued to the marker. */
    private static void measureTurning(String place) {
        for (int yaw : new int[] {0, 90, 180, 270}) {
            run(place + " yaw " + yaw, () -> CameraAngles.addMouseInput(QUARTER_TURN_PIXELS, 0.0));
            sleep(40);
            run(place + " measure " + yaw, () -> {
                double offset = groundOffset();
                double center = markerFromCenter();
                log(String.format(Locale.ROOT, "%s yaw=%d playerY=%d slicing=%s terrainOffset=%.1f markerFromCenter=%.1f",
                        place, yaw, mc().player.getBlockY(), MapRuntime.terrain().isSlicing(), offset, center));
                // A player far above or below the terrain is lifted off it on purpose, up to the clamp: only the rest counts here.
                double unexplained = offset - center;
                check(place + ": terrain stays under the marker at yaw " + yaw + " (" + Math.round(unexplained) + ")", unexplained < MAX_GROUND_OFFSET);
                check(place + ": marker stays in the centre at yaw " + yaw + " (" + Math.round(center) + ")", center < MAX_GROUND_OFFSET);
            });
            shot("27_" + place + "_yaw_" + yaw);
        }
    }

    private static void sleepFrames() {
        waitFor("frame samples", () -> frameCount >= FRAME_SAMPLES, 20 * 30);
    }

    private static int countRows() {
        return (int) children().stream().filter(c -> c.getClass().getSimpleName().equals("Row")).count();
    }
}
