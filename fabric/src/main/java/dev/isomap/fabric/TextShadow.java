package dev.isomap.fabric;

/**
 * NeoForge's {@code EditBox.setTextShadow}, rebuilt for Fabric with two mixins. Lives outside the mixin package, which
 * other code must not reference.
 */
public final class TextShadow {
    /** True while a text field without shadow is drawing. Only touched on the render thread. */
    public static boolean suppressed;

    private TextShadow() {}

    /** Added to every text field by the mixin. */
    public interface Switch {
        void isomap$setTextShadow(boolean shadow);
    }
}
