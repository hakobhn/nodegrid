package com.nodegrid.node;

import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Finds the IPv4 address other machines on the LAN should use to reach this node.
 *
 * <p>{@link InetAddress#getLocalHost()} is unreliable for this: on Windows it frequently returns the address of a
 * virtual adapter (Hyper-V / WSL "vEthernet", VirtualBox, VMware, VPN, Docker), which other machines cannot reach.
 * Instead we ask the OS which local address it would use for outbound traffic (the default-route interface), and
 * fall back to scanning physical, up, non-loopback interfaces.
 */
public final class HostAddressResolver {

    private static final List<String> VIRTUAL_HINTS = List.of(
            "vethernet", "virtual", "vmware", "vbox", "virtualbox", "hyper-v", "wsl", "docker", "veth", "br-",
            "tap", "tun", "vpn", "wireguard", "zerotier", "tailscale", "hamachi", "loopback", "bluetooth",
            "pseudo", "teredo", "isatap", "npcap", "utun", "awdl", "llw");

    private HostAddressResolver() {
    }

    /** Best guess for the LAN address of this machine; never null. */
    public static String resolve() {
        String viaRoute = defaultRouteAddress();
        if (viaRoute != null) {
            return viaRoute;
        }
        List<String> candidates = candidates();
        if (!candidates.isEmpty()) {
            return candidates.get(0);
        }
        try {
            return InetAddress.getLocalHost().getHostAddress();
        } catch (Exception e) {
            return "localhost";
        }
    }

    /**
     * Local address used to reach the outside world. A UDP "connect" sends no packets; it only makes the OS pick a
     * route and source address, so this works offline as long as a default route exists.
     */
    static String defaultRouteAddress() {
        for (String target : List.of("8.8.8.8", "192.168.0.1", "10.0.0.1")) {
            try (DatagramSocket socket = new DatagramSocket()) {
                socket.connect(new InetSocketAddress(target, 53));
                InetAddress local = socket.getLocalAddress();
                NetworkInterface nic = local == null ? null : NetworkInterface.getByInetAddress(local);
                if (local instanceof Inet4Address && usable(local) && (nic == null || !looksVirtual(nic))) {
                    return local.getHostAddress();
                }
            } catch (Exception ignored) {
                // try next target
            }
        }
        return null;
    }

    /** All usable IPv4 addresses on physical-looking interfaces, private LAN ranges first. */
    public static List<String> candidates() {
        List<InetAddress> found = new ArrayList<>();
        try {
            for (NetworkInterface nic : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nic.isUp() || nic.isLoopback() || nic.isVirtual() || nic.isPointToPoint() || looksVirtual(nic)) {
                    continue;
                }
                for (InetAddress addr : Collections.list(nic.getInetAddresses())) {
                    if (addr instanceof Inet4Address && usable(addr)) {
                        found.add(addr);
                    }
                }
            }
        } catch (Exception ignored) {
            // fall through with whatever we found
        }
        found.sort(Comparator.comparing((InetAddress a) -> !a.isSiteLocalAddress()));
        return found.stream().map(InetAddress::getHostAddress).distinct().toList();
    }

    private static boolean usable(InetAddress addr) {
        return !addr.isLoopbackAddress() && !addr.isLinkLocalAddress() && !addr.isAnyLocalAddress()
                && !addr.isMulticastAddress();
    }

    private static boolean looksVirtual(NetworkInterface nic) {
        String text = (nic.getName() + " " + nic.getDisplayName()).toLowerCase(Locale.ROOT);
        return VIRTUAL_HINTS.stream().anyMatch(text::contains);
    }
}
