package com.arc4io.archud.client.console;

import com.arc4io.archud.client.map.ShareInbox;

/**
 * Команды, которые обрабатывает сам клиент (не уходят на сервер): кнопки в чате «Принять / Отклонить» метку и т. п.
 */
public final class ClientCommands {
    private ClientCommands() {}

    /** true — команда наша и уже выполнена. */
    public static boolean handle(String command) {
        if (command == null) return false;
        String c = command.startsWith("/") ? command.substring(1) : command;
        String[] p = c.trim().split("\\s+");
        if (p.length == 0) return false;
        if (p[0].equals("archud_share") && p.length >= 3) {
            try {
                int id = Integer.parseInt(p[2]);
                if (p[1].equals("accept")) ShareInbox.accept(id);
                else ShareInbox.decline(id);
            } catch (NumberFormatException ignored) {
            }
            return true;
        }
        return false;
    }
}
