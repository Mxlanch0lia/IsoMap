package dev.isomap.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleSupplier;
import java.util.function.IntSupplier;

/** One persistent value. Memory is the only source of truth while the game runs; the file is written from it. */
public abstract class Setting<T> {
    private final String path;
    private final T defaultValue;
    private T value;

    private Setting(String path, T defaultValue) {
        this.path = path;
        this.defaultValue = defaultValue;
        this.value = defaultValue;
    }

    /** Dotted name, used both as the key in the file and in the translation keys. */
    public String path() {
        return path;
    }

    public T get() {
        return value;
    }

    public T getDefault() {
        return defaultValue;
    }

    public void set(T newValue) {
        T clean = sanitize(newValue);
        if (!clean.equals(value)) {
            value = clean;
            IsoMapConfig.markDirty();
        }
    }

    public void reset() {
        set(defaultValue);
    }

    /** Takes a value from the file; anything unusable leaves the default in place. */
    void read(JsonElement json) {
        T parsed = parse(json);
        value = parsed == null ? defaultValue : sanitize(parsed);
    }

    abstract JsonElement write();

    abstract T parse(JsonElement json);

    T sanitize(T raw) {
        return raw;
    }

    public static final class Bool extends Setting<Boolean> implements BooleanSupplier {
        Bool(String path, boolean defaultValue) {
            super(path, defaultValue);
        }

        @Override
        public boolean getAsBoolean() {
            return get();
        }

        @Override
        JsonElement write() {
            return new JsonPrimitive(get());
        }

        @Override
        Boolean parse(JsonElement json) {
            return json.isJsonPrimitive() && json.getAsJsonPrimitive().isBoolean() ? json.getAsBoolean() : null;
        }
    }

    public static final class Int extends Setting<Integer> implements IntSupplier {
        private final int min;
        private final int max;

        Int(String path, int defaultValue, int min, int max) {
            super(path, defaultValue);
            this.min = min;
            this.max = max;
        }

        public int min() {
            return min;
        }

        public int max() {
            return max;
        }

        @Override
        public int getAsInt() {
            return get();
        }

        @Override
        Integer sanitize(Integer raw) {
            return Math.max(min, Math.min(max, raw));
        }

        @Override
        JsonElement write() {
            return new JsonPrimitive(get());
        }

        @Override
        Integer parse(JsonElement json) {
            return json.isJsonPrimitive() && json.getAsJsonPrimitive().isNumber() ? json.getAsInt() : null;
        }
    }

    public static final class Decimal extends Setting<Double> implements DoubleSupplier {
        private final double min;
        private final double max;

        Decimal(String path, double defaultValue, double min, double max) {
            super(path, defaultValue);
            this.min = min;
            this.max = max;
        }

        public double min() {
            return min;
        }

        public double max() {
            return max;
        }

        @Override
        public double getAsDouble() {
            return get();
        }

        @Override
        Double sanitize(Double raw) {
            return raw.isNaN() ? getDefault() : Math.max(min, Math.min(max, raw));
        }

        @Override
        JsonElement write() {
            return new JsonPrimitive(get());
        }

        @Override
        Double parse(JsonElement json) {
            return json.isJsonPrimitive() && json.getAsJsonPrimitive().isNumber() ? json.getAsDouble() : null;
        }
    }

    public static final class Choice<E extends Enum<E>> extends Setting<E> {
        private final Class<E> type;

        Choice(String path, E defaultValue) {
            super(path, defaultValue);
            this.type = defaultValue.getDeclaringClass();
        }

        public List<E> constants() {
            return List.of(type.getEnumConstants());
        }

        @Override
        JsonElement write() {
            return new JsonPrimitive(get().name());
        }

        @Override
        E parse(JsonElement json) {
            if (json.isJsonPrimitive() && json.getAsJsonPrimitive().isString()) {
                for (E constant : type.getEnumConstants()) {
                    if (constant.name().equals(json.getAsString())) {
                        return constant;
                    }
                }
            }
            return null;
        }
    }
}
