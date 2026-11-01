package com.arc4io.archud.client.fx.pack;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Пользовательские uniform-переменные шейдерпака (shaders.properties, формат OptiFine / Iris):
 * {@code uniform.float.rainFactor = smooth(1, rainStrength, 3, 3)}, {@code variable.float.x = …}.
 * Свой маленький вычислитель выражений: арифметика, сравнения, логика, if / smooth / clamp / in / between и др.,
 * компоненты векторов (eyeBrightness.y), биомы (biome, biome_precipitation, BIOME_*).
 */
final class CustomUniforms {
    private record Def(String type, String name, Node expr, boolean uniform) {}

    private interface Node {
        Object eval(CustomUniforms ctx);
    }

    private final List<Def> defs = new ArrayList<>();
    private final Map<String, Object> vars = new HashMap<>();
    private final Map<Integer, double[]> smooth = new HashMap<>(); // id → {значение, время}
    private int autoSmoothId = 100000;
    private long lastNanos;
    private double dt;

    static CustomUniforms parse(String props) {
        CustomUniforms c = new CustomUniforms();
        if (props == null) return c;
        for (String raw : props.replace("\r", "").split("\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            boolean uni = line.startsWith("uniform."), var = line.startsWith("variable.");
            if (!uni && !var) continue;
            int eq = line.indexOf('=');
            if (eq < 0) continue;
            String[] head = line.substring(0, eq).trim().split("\\.");
            if (head.length < 3) continue;
            try {
                Node n = new Parser(line.substring(eq + 1).trim(), c).parse();
                c.defs.add(new Def(head[1], head[2], n, uni));
            } catch (Exception ignored) {
                // выражение, которое не поняли, просто пропускается
            }
        }
        return c;
    }

    int size() {
        return defs.size();
    }

    /** Посчитать на кадр и положить в uniform-переменные. */
    void update(Map<String, Object> out) {
        long now = System.nanoTime();
        dt = lastNanos == 0 ? 0 : Math.min(1.0, (now - lastNanos) / 1e9);
        lastNanos = now;
        vars.clear();
        biome();
        for (Def d : defs) {
            Object v;
            try {
                v = d.expr.eval(this);
            } catch (Exception e) {
                v = 0.0;
            }
            Object typed = switch (d.type) {
                case "int" -> (int) num(v);
                case "bool" -> num(v) != 0 ? 1 : 0;
                case "vec2", "vec3", "vec4" -> {
                    double[] a = vec(v);
                    float[] f = new float[a.length];
                    for (int i = 0; i < a.length; i++) f[i] = (float) a[i];
                    yield f;
                }
                default -> (float) num(v);
            };
            vars.put(d.name, v);
            if (d.uniform) out.put(d.name, typed);
        }
    }

    private void biome() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        BlockPos p = mc.player.blockPosition();
        var holder = mc.level.getBiome(p);
        var reg = mc.level.registryAccess().registryOrThrow(Registries.BIOME);
        vars.put("biome", (double) reg.getId(holder.value()));
        var prec = holder.value().getPrecipitationAt(p);
        vars.put("biome_precipitation", (double) (prec.ordinal())); // NONE 0, RAIN 1, SNOW 2
        vars.put("temperature", (double) holder.value().getBaseTemperature());
        vars.put("rainfall", 0.5);
        vars.put("is_burning", mc.player.isOnFire() ? 1.0 : 0.0);
        vars.put("is_sneaking", mc.player.isShiftKeyDown() ? 1.0 : 0.0);
        vars.put("is_sprinting", mc.player.isSprinting() ? 1.0 : 0.0);
        vars.put("is_on_ground", mc.player.onGround() ? 1.0 : 0.0);
        vars.put("is_in_water", mc.player.isInWater() ? 1.0 : 0.0);
        vars.put("is_in_lava", mc.player.isInLava() ? 1.0 : 0.0);
        vars.put("is_invisible", mc.player.isInvisible() ? 1.0 : 0.0);
        vars.put("is_hurt", mc.player.hurtTime > 0 ? 1.0 : 0.0);
        vars.put("is_alive", mc.player.isAlive() ? 1.0 : 0.0);
        vars.put("player_health", (double) mc.player.getHealth());
        vars.put("max_player_health", (double) mc.player.getMaxHealth());
        vars.put("player_food_level", (double) mc.player.getFoodData().getFoodLevel());
        vars.put("isElytraFlying", mc.player.isFallFlying() ? 1.0 : 0.0);
    }

    /** Значение переменной: свои, пользовательские, стандартные uniform-переменные, константы биомов. */
    Object lookup(String name) {
        Object v = vars.get(name);
        if (v != null) return v;
        Object u = PackUniforms.VALUES.get(name);
        if (u != null) return toValue(u);
        switch (name) {
            case "pi":
                return Math.PI;
            case "true":
                return 1.0;
            case "false":
                return 0.0;
            default:
        }
        if (name.startsWith("BIOME_")) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level != null) {
                var reg = mc.level.registryAccess().registryOrThrow(Registries.BIOME);
                var b = reg.get(new net.minecraft.resources.ResourceLocation(name.substring(6).toLowerCase(Locale.ROOT)));
                return b == null ? -1.0 : (double) reg.getId(b);
            }
            return -1.0;
        }
        if (name.startsWith("CAT_")) return -1.0;
        return 0.0;
    }

    private static Object toValue(Object u) {
        if (u instanceof Number n) return n.doubleValue();
        if (u instanceof float[] f) {
            double[] d = new double[f.length];
            for (int i = 0; i < f.length; i++) d[i] = f[i];
            return d;
        }
        if (u instanceof int[] a) {
            double[] d = new double[a.length];
            for (int i = 0; i < a.length; i++) d[i] = a[i];
            return d;
        }
        if (u instanceof Matrix4f) return 0.0;
        return 0.0;
    }

    static double num(Object v) {
        if (v instanceof Double d) return d;
        if (v instanceof double[] a) return a.length > 0 ? a[0] : 0;
        return 0;
    }

    static double[] vec(Object v) {
        if (v instanceof double[] a) return a;
        return new double[]{num(v)};
    }

    /** smooth(id, value, fadeUp, fadeDown): плавное приближение к значению (секунды). */
    double smooth(int id, double target, double up, double down) {
        double[] s = smooth.get(id);
        if (s == null) {
            smooth.put(id, new double[]{target});
            return target;
        }
        double t = target > s[0] ? up : down;
        if (t <= 0) s[0] = target;
        else s[0] += (target - s[0]) * (1 - Math.exp(-dt * 4.6 / t)); // ~99% за t секунд
        return s[0];
    }

    int nextSmoothId() {
        return autoSmoothId++;
    }

    // ------------------------------------------------------------------ разбор выражений
    private static final class Parser {
        private final String s;
        private final CustomUniforms ctx;
        private int pos;

        Parser(String s, CustomUniforms ctx) {
            this.s = s;
            this.ctx = ctx;
        }

        Node parse() {
            Node n = or();
            skip();
            if (pos != s.length()) throw new IllegalArgumentException("trailing: " + s.substring(pos));
            return n;
        }

        private void skip() {
            while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) pos++;
        }

        private boolean eat(String t) {
            skip();
            if (s.startsWith(t, pos)) {
                pos += t.length();
                return true;
            }
            return false;
        }

        private Node or() {
            Node l = and();
            while (eat("||")) {
                Node a = l, b = and();
                l = c -> num(a.eval(c)) != 0 || num(b.eval(c)) != 0 ? 1.0 : 0.0;
            }
            return l;
        }

        private Node and() {
            Node l = cmp();
            while (eat("&&")) {
                Node a = l, b = cmp();
                l = c -> num(a.eval(c)) != 0 && num(b.eval(c)) != 0 ? 1.0 : 0.0;
            }
            return l;
        }

        private Node cmp() {
            Node l = add();
            while (true) {
                Node a = l;
                if (eat("==")) {
                    Node b = add();
                    l = c -> num(a.eval(c)) == num(b.eval(c)) ? 1.0 : 0.0;
                } else if (eat("!=")) {
                    Node b = add();
                    l = c -> num(a.eval(c)) != num(b.eval(c)) ? 1.0 : 0.0;
                } else if (eat("<=")) {
                    Node b = add();
                    l = c -> num(a.eval(c)) <= num(b.eval(c)) ? 1.0 : 0.0;
                } else if (eat(">=")) {
                    Node b = add();
                    l = c -> num(a.eval(c)) >= num(b.eval(c)) ? 1.0 : 0.0;
                } else if (eat("<")) {
                    Node b = add();
                    l = c -> num(a.eval(c)) < num(b.eval(c)) ? 1.0 : 0.0;
                } else if (eat(">")) {
                    Node b = add();
                    l = c -> num(a.eval(c)) > num(b.eval(c)) ? 1.0 : 0.0;
                } else {
                    return l;
                }
            }
        }

        private Node add() {
            Node l = mul();
            while (true) {
                Node a = l;
                if (eat("+")) {
                    Node b = mul();
                    l = c -> op(a.eval(c), b.eval(c), '+');
                } else if (eat("-")) {
                    Node b = mul();
                    l = c -> op(a.eval(c), b.eval(c), '-');
                } else {
                    return l;
                }
            }
        }

        private Node mul() {
            Node l = unary();
            while (true) {
                Node a = l;
                if (eat("*")) {
                    Node b = unary();
                    l = c -> op(a.eval(c), b.eval(c), '*');
                } else if (eat("/")) {
                    Node b = unary();
                    l = c -> op(a.eval(c), b.eval(c), '/');
                } else if (eat("%")) {
                    Node b = unary();
                    l = c -> op(a.eval(c), b.eval(c), '%');
                } else {
                    return l;
                }
            }
        }

        private Node unary() {
            if (eat("!")) {
                Node a = unary();
                return c -> num(a.eval(c)) == 0 ? 1.0 : 0.0;
            }
            if (eat("-")) {
                Node a = unary();
                return c -> op(0.0, a.eval(c), '-');
            }
            if (eat("+")) return unary();
            return postfix(primary());
        }

        private Node postfix(Node n) {
            while (true) {
                skip();
                if (pos < s.length() && s.charAt(pos) == '.' && pos + 1 < s.length() && Character.isLetter(s.charAt(pos + 1))) {
                    pos++;
                    String comp = ident();
                    Node base = n;
                    n = c -> swizzle(base.eval(c), comp);
                } else {
                    return n;
                }
            }
        }

        private String ident() {
            int st = pos;
            while (pos < s.length() && (Character.isLetterOrDigit(s.charAt(pos)) || s.charAt(pos) == '_')) pos++;
            return s.substring(st, pos);
        }

        private Node primary() {
            skip();
            if (eat("(")) {
                Node n = or();
                if (!eat(")")) throw new IllegalArgumentException(")");
                return n;
            }
            char ch = pos < s.length() ? s.charAt(pos) : 0;
            if (Character.isDigit(ch) || ch == '.') {
                int st = pos;
                while (pos < s.length() && (Character.isDigit(s.charAt(pos)) || s.charAt(pos) == '.' || s.charAt(pos) == 'e' || s.charAt(pos) == 'E'
                        || ((s.charAt(pos) == '-' || s.charAt(pos) == '+') && (s.charAt(pos - 1) == 'e' || s.charAt(pos - 1) == 'E')))) pos++;
                String t = s.substring(st, pos);
                if (pos < s.length() && (s.charAt(pos) == 'f' || s.charAt(pos) == 'F')) pos++;
                double v = Double.parseDouble(t);
                return c -> v;
            }
            if (!Character.isLetter(ch) && ch != '_') throw new IllegalArgumentException("unexpected " + ch);
            String name = ident();
            if (eat("(")) {
                List<Node> args = new ArrayList<>();
                if (!eat(")")) {
                    do args.add(or()); while (eat(","));
                    if (!eat(")")) throw new IllegalArgumentException(")");
                }
                return call(name, args);
            }
            return c -> c.lookup(name);
        }

        private Node call(String f, List<Node> a) {
            switch (f) {
                case "if" -> {
                    return c -> {
                        int i = 0;
                        for (; i + 1 < a.size(); i += 2) if (num(a.get(i).eval(c)) != 0) return a.get(i + 1).eval(c);
                        return i < a.size() ? a.get(i).eval(c) : 0.0;
                    };
                }
                case "smooth" -> {
                    // smooth([id,] value [, fadeUp [, fadeDown]])
                    boolean hasId = a.size() >= 2 && a.size() != 2 || a.size() == 4;
                    int id = hasId ? (int) num(a.get(0).eval(ctx)) : ctx.nextSmoothId();
                    Node val = a.get(hasId ? 1 : 0);
                    Node up = a.size() > (hasId ? 2 : 1) ? a.get(hasId ? 2 : 1) : null;
                    Node down = a.size() > (hasId ? 3 : 2) ? a.get(hasId ? 3 : 2) : up;
                    return c -> c.smooth(id, num(val.eval(c)), up == null ? 1 : num(up.eval(c)), down == null ? 1 : num(down.eval(c)));
                }
                case "in" -> {
                    return c -> {
                        double x = num(a.get(0).eval(c));
                        for (int i = 1; i < a.size(); i++) if (num(a.get(i).eval(c)) == x) return 1.0;
                        return 0.0;
                    };
                }
                case "between" -> {
                    return c -> {
                        double x = num(a.get(0).eval(c));
                        return x >= num(a.get(1).eval(c)) && x <= num(a.get(2).eval(c)) ? 1.0 : 0.0;
                    };
                }
                case "equals" -> {
                    return c -> Math.abs(num(a.get(0).eval(c)) - num(a.get(1).eval(c))) <= (a.size() > 2 ? num(a.get(2).eval(c)) : 1e-6) ? 1.0 : 0.0;
                }
                case "vec2", "vec3", "vec4" -> {
                    int n = f.charAt(3) - '0';
                    return c -> {
                        double[] v = new double[n];
                        for (int i = 0; i < n; i++) v[i] = i < a.size() ? num(a.get(i).eval(c)) : (a.size() == 1 ? num(a.get(0).eval(c)) : 0);
                        return v;
                    };
                }
                default -> {
                    return c -> {
                        double x = a.isEmpty() ? 0 : num(a.get(0).eval(c)), y = a.size() > 1 ? num(a.get(1).eval(c)) : 0;
                        return switch (f) {
                            case "sin" -> Math.sin(x);
                            case "cos" -> Math.cos(x);
                            case "tan" -> Math.tan(x);
                            case "asin" -> Math.asin(x);
                            case "acos" -> Math.acos(x);
                            case "atan" -> a.size() > 1 ? Math.atan2(x, y) : Math.atan(x);
                            case "atan2" -> Math.atan2(x, y);
                            case "torad" -> Math.toRadians(x);
                            case "todeg" -> Math.toDegrees(x);
                            case "min" -> {
                                double m = x;
                                for (int i = 1; i < a.size(); i++) m = Math.min(m, num(a.get(i).eval(c)));
                                yield m;
                            }
                            case "max" -> {
                                double m = x;
                                for (int i = 1; i < a.size(); i++) m = Math.max(m, num(a.get(i).eval(c)));
                                yield m;
                            }
                            case "clamp" -> Math.max(y, Math.min(num(a.get(2).eval(c)), x));
                            case "abs" -> Math.abs(x);
                            case "floor" -> Math.floor(x);
                            case "ceil" -> Math.ceil(x);
                            case "round" -> Math.rint(x);
                            case "frac" -> x - Math.floor(x);
                            case "exp" -> Math.exp(x);
                            case "log" -> Math.log(x);
                            case "pow" -> Math.pow(x, y);
                            case "sqrt" -> Math.sqrt(x);
                            case "fmod" -> x - y * Math.floor(x / y);
                            case "signum" -> Math.signum(x);
                            case "hypot" -> Math.hypot(x, y);
                            case "random" -> Math.random();
                            default -> 0.0;
                        };
                    };
                }
            }
        }
    }

    private static Object swizzle(Object v, String comp) {
        double[] a = vec(v);
        if (comp.length() == 1) {
            int i = idx(comp.charAt(0));
            return i < a.length ? a[i] : 0.0;
        }
        double[] out = new double[comp.length()];
        for (int k = 0; k < comp.length(); k++) {
            int i = idx(comp.charAt(k));
            out[k] = i < a.length ? a[i] : 0;
        }
        return out;
    }

    private static int idx(char c) {
        return switch (c) {
            case 'x', 'r', 's' -> 0;
            case 'y', 'g', 't' -> 1;
            case 'z', 'b', 'p' -> 2;
            default -> 3;
        };
    }

    private static Object op(Object x, Object y, char o) {
        if (x instanceof double[] || y instanceof double[]) {
            double[] a = vec(x), b = vec(y);
            int n = Math.max(a.length, b.length);
            double[] r = new double[n];
            for (int i = 0; i < n; i++) r[i] = op1(a[Math.min(i, a.length - 1)], b[Math.min(i, b.length - 1)], o);
            return r;
        }
        return op1(num(x), num(y), o);
    }

    private static double op1(double a, double b, char o) {
        return switch (o) {
            case '+' -> a + b;
            case '-' -> a - b;
            case '*' -> a * b;
            case '/' -> b == 0 ? 0 : a / b;
            default -> b == 0 ? 0 : a - b * Math.floor(a / b);
        };
    }
}
