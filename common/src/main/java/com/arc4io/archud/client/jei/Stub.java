package com.arc4io.archud.client.jei;

import net.minecraft.network.chat.Component;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * Реализация любого интерфейса API JEI «по месту»: нужные методы задаются лямбдами (по имени и числу аргументов),
 * методы по умолчанию интерфейса работают как есть, остальные возвращают безопасное значение — сам объект для
 * цепочек (builder.setX().setY()), пустой список/Optional/поток, 0/false, или ещё одну такую же заглушку.
 * Так плагины модов для JEI работают целиком, даже если вызывают то, что у нас не нужно.
 */
public final class Stub implements InvocationHandler {
    private final Class<?> iface;
    private final Map<String, Function<Object[], Object>> handlers = new HashMap<>();
    private Object self;

    private Stub(Class<?> iface) {
        this.iface = iface;
    }

    public static <T> Builder<T> of(Class<T> iface) {
        return new Builder<>(iface);
    }

    @SuppressWarnings("unchecked")
    public static <T> T empty(Class<T> iface) {
        return of(iface).build();
    }

    public static final class Builder<T> {
        private final Stub stub;
        private final Class<T> iface;

        Builder(Class<T> iface) {
            this.iface = iface;
            this.stub = new Stub(iface);
        }

        /** Обработчик метода name с n аргументами (-1 — любое число). */
        public Builder<T> on(String name, int n, Function<Object[], Object> f) {
            stub.handlers.put(name + "/" + n, f);
            return this;
        }

        public Builder<T> on(String name, Function<Object[], Object> f) {
            return on(name, -1, f);
        }

        @SuppressWarnings("unchecked")
        public T build() {
            T p = (T) Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[]{iface}, stub);
            stub.self = p;
            return p;
        }
    }

    @Override
    public Object invoke(Object proxy, Method m, Object[] args) throws Throwable {
        Object[] a = args == null ? new Object[0] : args;
        String name = m.getName();
        if (m.getDeclaringClass() == Object.class) {
            return switch (name) {
                case "equals" -> proxy == a[0];
                case "hashCode" -> System.identityHashCode(proxy);
                case "toString" -> "ArcJeiStub[" + iface.getSimpleName() + "]";
                default -> null;
            };
        }
        Function<Object[], Object> h = handlers.get(name + "/" + a.length);
        if (h == null) h = handlers.get(name + "/-1");
        if (h != null) return h.apply(a);
        if (m.isDefault()) return InvocationHandler.invokeDefault(proxy, m, args);
        return fallback(m.getReturnType(), proxy);
    }

    /** Безопасное значение для метода, который мы не реализовали. */
    static Object fallback(Class<?> r, Object proxy) {
        if (r == void.class) return null;
        if (r == boolean.class) return false;
        if (r == int.class) return 0;
        if (r == long.class) return 0L;
        if (r == float.class) return 0F;
        if (r == double.class) return 0D;
        if (r == short.class) return (short) 0;
        if (r == byte.class) return (byte) 0;
        if (r == char.class) return (char) 0;
        if (r == Optional.class) return Optional.empty();
        if (r == Stream.class) return Stream.empty();
        if (r == List.class || r == Collection.class || r == Iterable.class) return new ArrayList<>();
        if (r == Set.class) return new java.util.HashSet<>();
        if (r == Map.class) return new HashMap<>();
        if (r == String.class) return "";
        if (r == Component.class) return Component.empty();
        if (r.isInstance(proxy)) return proxy; // цепочки вызовов
        if (r.isInterface() && r.getName().startsWith("mezz.jei.api")) return empty(r);
        return null;
    }
}
