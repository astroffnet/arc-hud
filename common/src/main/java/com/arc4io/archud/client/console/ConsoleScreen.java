package com.arc4io.archud.client.console;

import com.arc4io.archud.client.ArcKeys;
import com.arc4io.archud.client.hud.Draw;
import com.arc4io.archud.client.hud.Theme;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * Консоль как в играх на Source: открывается клавишей «~» (Ё), команды пишутся без «/», есть история (↑/↓),
 * дополнение по Tab и весь вывод чата и сервера. Своё: «clear» — очистить, «help» — подсказка.
 */
public class ConsoleScreen extends net.minecraft.client.gui.screens.Screen {
    private static final List<Component> LOG = new ArrayList<>();
    private static final List<String> HISTORY = new ArrayList<>();
    private static final int MAX_LOG = 600;
    private EditBox input;
    private int historyPos = -1;
    private double scroll;
    private List<String> suggestions = List.of();

    public ConsoleScreen() {
        super(Component.translatable("archud.console.title"));
    }

    /** Всё, что появляется в чате, попадает и в консоль. */
    public static void log(Component msg) {
        if (msg == null) return;
        synchronized (LOG) {
            LOG.add(msg);
            while (LOG.size() > MAX_LOG) LOG.remove(0);
        }
    }

    private int panelH() {
        return Math.max(120, (int) (height * 0.62));
    }

    @Override
    protected void init() {
        String old = input == null ? "" : input.getValue();
        input = new EditBox(font, 18, panelH() - 18, width - 26, 14, Component.translatable("archud.console.title"));
        input.setMaxLength(32500);
        input.setBordered(false);
        input.setValue(old);
        input.setResponder(s -> suggestions = List.of());
        addRenderableWidget(input);
        setInitialFocus(input);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (ArcKeys.CONSOLE.matches(key, scan) && input.getValue().isEmpty()) {
            onClose();
            return true;
        }
        switch (key) {
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> {
                run(input.getValue());
                input.setValue("");
                historyPos = -1;
                scroll = 0;
                return true;
            }
            case GLFW.GLFW_KEY_UP -> {
                if (HISTORY.isEmpty()) return true;
                historyPos = historyPos < 0 ? HISTORY.size() - 1 : Math.max(0, historyPos - 1);
                input.setValue(HISTORY.get(historyPos));
                return true;
            }
            case GLFW.GLFW_KEY_DOWN -> {
                if (historyPos < 0) return true;
                historyPos++;
                if (historyPos >= HISTORY.size()) {
                    historyPos = -1;
                    input.setValue("");
                } else {
                    input.setValue(HISTORY.get(historyPos));
                }
                return true;
            }
            case GLFW.GLFW_KEY_TAB -> {
                complete();
                return true;
            }
            case GLFW.GLFW_KEY_PAGE_UP -> {
                scroll += 10;
                return true;
            }
            case GLFW.GLFW_KEY_PAGE_DOWN -> {
                scroll = Math.max(0, scroll - 10);
                return true;
            }
            default -> {
            }
        }
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public boolean charTyped(char c, int mods) {
        // «~» / «ё» сразу после открытия не печатается
        if ((c == '`' || c == '~' || c == 'ё' || c == 'Ё') && input.getValue().isEmpty()) return true;
        return super.charTyped(c, mods);
    }

    private void run(String raw) {
        String cmd = raw.trim();
        if (cmd.isEmpty()) return;
        if (HISTORY.isEmpty() || !HISTORY.get(HISTORY.size() - 1).equals(cmd)) HISTORY.add(cmd);
        while (HISTORY.size() > 100) HISTORY.remove(0);
        if (cmd.startsWith("/")) cmd = cmd.substring(1);
        log(Component.literal("] " + cmd).withStyle(ChatFormatting.GRAY));
        String lower = cmd.toLowerCase(java.util.Locale.ROOT);
        if (lower.equals("clear") || lower.equals("cls")) {
            synchronized (LOG) {
                LOG.clear();
            }
            return;
        }
        if (lower.equals("help") || lower.equals("?")) {
            log(Component.translatable("archud.console.help").withStyle(ChatFormatting.YELLOW));
            return;
        }
        if (lower.equals("exit") || lower.equals("close")) {
            onClose();
            return;
        }
        if (minecraft == null || minecraft.getConnection() == null) return;
        if (ClientCommands.handle(cmd)) return;
        minecraft.getConnection().sendCommand(cmd);
    }

    /** Tab: дополнение команды по дереву команд сервера (как в чате). */
    private void complete() {
        if (minecraft == null || minecraft.getConnection() == null) return;
        String text = input.getValue();
        String cmd = text.startsWith("/") ? text.substring(1) : text;
        try {
            var dispatcher = minecraft.getConnection().getCommands();
            var parse = dispatcher.parse(cmd, minecraft.getConnection().getSuggestionsProvider());
            var future = dispatcher.getCompletionSuggestions(parse, cmd.length());
            var sug = future.getNow(null);
            if (sug == null || sug.isEmpty()) return;
            List<String> list = new ArrayList<>();
            for (var s : sug.getList()) list.add(s.getText());
            if (list.size() == 1) {
                input.setValue(sug.getList().get(0).apply(cmd) + " ");
                suggestions = List.of();
            } else {
                suggestions = list.subList(0, Math.min(list.size(), 40));
                // общий префикс подставляется сразу
                String common = list.get(0);
                for (String s : list) while (!s.startsWith(common)) common = common.substring(0, common.length() - 1);
                int start = sug.getRange().getStart();
                if (common.length() > cmd.length() - start) input.setValue(cmd.substring(0, start) + common);
            }
        } catch (Exception ignored) {
        }
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        scroll = Math.max(0, scroll + delta * 3);
        return true;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float pt) {
        int ph = panelH();
        int accent = Theme.accent();
        g.fill(0, 0, width, ph, 0xE60B0E12);
        g.fill(0, ph, width, ph + 1, Draw.alpha(accent, 0xFF));
        g.drawString(font, title, 6, 4, Draw.alpha(accent, 0xFF), true);
        String hint = Component.translatable("archud.console.hint").getString();
        g.drawString(font, hint, width - 6 - font.width(hint), 4, Draw.TEXT_DIM, false);
        // вывод: снизу вверх, с переносом строк
        int top = 16, bottom = ph - 24;
        List<FormattedCharSequence> lines = new ArrayList<>();
        synchronized (LOG) {
            for (Component c : LOG) lines.addAll(font.split(c, width - 16));
        }
        int visible = (bottom - top) / 10;
        scroll = Mth.clamp(scroll, 0, Math.max(0, lines.size() - visible));
        int end = lines.size() - (int) scroll;
        g.enableScissor(0, top, width, bottom);
        int y = bottom - 10;
        for (int i = end - 1; i >= 0 && y >= top - 10; i--, y -= 10) g.drawString(font, lines.get(i), 8, y, 0xFFE0E6EE, false);
        g.disableScissor();
        if (scroll > 0) g.drawString(font, "▼ " + (int) scroll, width - 40, bottom - 10, 0xFFFFD54F, false);
        // строка ввода
        g.fill(4, ph - 21, width - 4, ph - 4, 0x60000000);
        g.drawString(font, "]", 9, ph - 18, Draw.alpha(accent, 0xFF), false);
        super.render(g, mouseX, mouseY, pt);
        if (!suggestions.isEmpty()) {
            int sx = 18, sy = ph + 4;
            int w = 0;
            for (String s : suggestions) w = Math.max(w, font.width(s));
            int rows = Math.min(suggestions.size(), 12);
            g.fill(sx - 2, sy - 2, sx + w + 4, sy + rows * 10 + 2, 0xE0101419);
            for (int i = 0; i < rows; i++) g.drawString(font, suggestions.get(i), sx, sy + i * 10, 0xFFB0BEC5, false);
        }
    }
}
