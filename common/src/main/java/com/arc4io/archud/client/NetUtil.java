package com.arc4io.archud.client;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/** Локальные IPv4-адреса компьютера (для «Открыть для сети»). */
public final class NetUtil {
    private NetUtil() {}

    /** Адрес в локальной сети (192.168.x.x / 10.x / 172.16-31.x) — первым, затем VPN (Hamachi, Radmin, ZeroTier…). */
    private static volatile List<String> cached;
    private static volatile long cachedAt;
    private static volatile boolean scanning;

    /**
     * Кэшированный список адресов. Перебор сетевых интерфейсов в Windows занимает десятки миллисекунд,
     * поэтому его нельзя делать каждый кадр: результат живёт 15 секунд и обновляется в фоновом потоке.
     */
    public static List<String> localAddresses() {
        List<String> c = cached;
        if (c == null) {
            c = scan();
            cached = c;
            cachedAt = System.currentTimeMillis();
            return c;
        }
        if (System.currentTimeMillis() - cachedAt > 15_000 && !scanning) {
            scanning = true;
            Thread t = new Thread(() -> {
                try {
                    cached = scan();
                    cachedAt = System.currentTimeMillis();
                } finally {
                    scanning = false;
                }
            }, "ArcHud-NetScan");
            t.setDaemon(true);
            t.start();
        }
        return c;
    }

    private static List<String> scan() {
        List<String> lan = new ArrayList<>(), other = new ArrayList<>();
        try {
            for (Iterator<NetworkInterface> it = NetworkInterface.getNetworkInterfaces().asIterator(); it.hasNext(); ) {
                NetworkInterface ni = it.next();
                if (!ni.isUp() || ni.isLoopback()) continue;
                String n = (ni.getDisplayName() + " " + ni.getName()).toLowerCase(Locale.ROOT);
                boolean virtualBox = n.contains("virtualbox") || n.contains("vmware") || n.contains("docker") || n.contains("vethernet")
                        || n.contains("hyper-v") || n.contains("wsl");
                if (virtualBox) continue;
                for (Iterator<InetAddress> ia = ni.getInetAddresses().asIterator(); ia.hasNext(); ) {
                    InetAddress a = ia.next();
                    if (!(a instanceof Inet4Address) || a.isLoopbackAddress() || a.isLinkLocalAddress()) continue;
                    String ip = a.getHostAddress();
                    if (a.isSiteLocalAddress() && !ni.isVirtual()) lan.add(ip);
                    else other.add(ip);
                }
            }
        } catch (Exception ignored) {
        }
        lan.addAll(other);
        if (lan.isEmpty()) lan.add("127.0.0.1");
        return java.util.Collections.unmodifiableList(lan);
    }

    public static String primaryAddress() {
        return localAddresses().get(0);
    }
}
