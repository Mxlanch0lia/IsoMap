package dev.isomap.client.render;

import dev.isomap.config.IsoMapConfig;
import net.minecraft.util.Mth;

/**
 * Free camera angles shared by the HUD map and the full map screen.
 *
 * <p>Yaw is the user's turn away from north-up; pitch is the elevation above the ground plane, where 90 degrees looks
 * straight down. Input moves the targets, drawing eases towards them.
 */
public final class CameraAngles {
    private static final double MOUSE_DEGREES_PER_PIXEL = 0.25;
    private static final double EASE_RATE = 18.0;
    private static final double RETURN_RATE = 3.0;
    private static final double TOP_VIEW_PITCH = 90.0;
    private static final double SETTLED_DEGREES = 0.03;

    private static double yawOffsetTarget;
    private static double yawOffset;
    private static double pitchTarget = 55.0;
    private static double pitch = 55.0;
    private static long lastInputNanos;
    private static boolean loaded;
    private static boolean dirty;
    private static double pitchBeforeTopView = -1.0;

    private CameraAngles() {}

    private static void ensureLoaded() {
        if (!loaded) {
            loaded = true;
            yawOffsetTarget = yawOffset = IsoMapConfig.STATE_YAW.get();
            pitchTarget = pitch = IsoMapConfig.STATE_PITCH.get();
            clampTargets();
            pitch = pitchTarget;
            yawOffset = yawOffsetTarget;
        }
    }

    private static void clampTargets() {
        int min = Math.min(IsoMapConfig.PITCH_MIN.getAsInt(), IsoMapConfig.PITCH_MAX.getAsInt());
        int max = Math.max(IsoMapConfig.PITCH_MIN.getAsInt(), IsoMapConfig.PITCH_MAX.getAsInt());
        if (pitchBeforeTopView < 0.0) {
            pitchTarget = Mth.clamp(pitchTarget, min, max);
        }
        int limit = IsoMapConfig.YAW_LIMIT.getAsInt();
        if (limit < 180) {
            yawOffsetTarget = Mth.clamp(yawOffsetTarget, -limit, limit);
        } else {
            // Unlimited rotation: keep the value small without a visible jump.
            double wrapped = Mth.wrapDegrees(yawOffsetTarget);
            yawOffset += wrapped - yawOffsetTarget;
            yawOffsetTarget = wrapped;
        }
    }

    /** Applies raw mouse movement in screen pixels. */
    public static void addMouseInput(double dx, double dy) {
        ensureLoaded();
        if (dx == 0.0 && dy == 0.0) {
            return;
        }
        double speed = MOUSE_DEGREES_PER_PIXEL * IsoMapConfig.SENSITIVITY.get();
        yawOffsetTarget += dx * speed * (IsoMapConfig.INVERT_YAW.getAsBoolean() ? -1.0 : 1.0);
        if (dy != 0.0) {
            pitchBeforeTopView = -1.0;
            pitchTarget += dy * speed * (IsoMapConfig.INVERT_PITCH.getAsBoolean() ? -1.0 : 1.0);
        }
        clampTargets();
        lastInputNanos = System.nanoTime();
        dirty = true;
    }

    public static void toggleTopView() {
        ensureLoaded();
        if (pitchBeforeTopView >= 0.0) {
            pitchTarget = pitchBeforeTopView;
            pitchBeforeTopView = -1.0;
        } else {
            pitchBeforeTopView = pitchTarget;
            pitchTarget = TOP_VIEW_PITCH;
        }
        dirty = true;
    }

    public static boolean isTopView() {
        return pitchBeforeTopView >= 0.0;
    }

    public static void reset() {
        ensureLoaded();
        yawOffsetTarget = 0.0;
        pitchTarget = IsoMapConfig.STATE_PITCH.getDefault();
        pitchBeforeTopView = -1.0;
        clampTargets();
        dirty = true;
    }

    /** Called once per frame by whichever view draws first. */
    public static void update(double dt) {
        ensureLoaded();
        clampTargets();

        int autoReturn = IsoMapConfig.AUTO_RETURN_SECONDS.getAsInt();
        if (autoReturn > 0 && yawOffsetTarget != 0.0 && System.nanoTime() - lastInputNanos > autoReturn * 1_000_000_000L) {
            yawOffsetTarget += -yawOffsetTarget * (1.0 - Math.exp(-dt * RETURN_RATE));
            if (Math.abs(yawOffsetTarget) < 0.01) {
                yawOffsetTarget = 0.0;
            }
            dirty = true;
        }

        double ease = 1.0 - Math.exp(-dt * EASE_RATE);
        yawOffset += (yawOffsetTarget - yawOffset) * ease;
        pitch += (pitchTarget - pitch) * ease;

    }

    /** True while the drawn angles have not caught up with where they are heading. */
    public static boolean isMoving() {
        ensureLoaded();
        return Math.abs(yawOffsetTarget - yawOffset) > SETTLED_DEGREES
                || Math.abs(pitchTarget - pitch) > SETTLED_DEGREES;
    }

    public static double yawDegrees() {
        return yawOffset;
    }

    public static double pitchDegrees() {
        return pitch;
    }

    public static double targetYawOffset() {
        return yawOffsetTarget;
    }

    public static double targetPitch() {
        return pitchTarget;
    }

    /** Copies the current angles into the settings now and then; a file write per mouse move would be wasteful. */
    public static void storeIfDirty() {
        if (dirty) {
            dirty = false;
            IsoMapConfig.STATE_YAW.set(Mth.wrapDegrees(yawOffsetTarget));
            IsoMapConfig.STATE_PITCH.set(pitchBeforeTopView >= 0.0 ? pitchBeforeTopView : pitchTarget);
        }
    }

    /** Forces a re-read of the stored angles, after the settings were reset. */
    public static void reloadFromConfig() {
        loaded = false;
        pitchBeforeTopView = -1.0;
        ensureLoaded();
        dirty = false;
    }

    public static double followAlpha(double dt) {
        int smoothing = IsoMapConfig.FOLLOW_SMOOTHING.getAsInt();
        return smoothing <= 0 ? 1.0 : 1.0 - Math.exp(-dt * 30.0 * Math.exp(-smoothing * 0.035));
    }
}
