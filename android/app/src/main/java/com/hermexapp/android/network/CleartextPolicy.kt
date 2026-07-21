package com.hermexapp.android.network

/**
 * When plain HTTP is allowed. The threat model: cleartext is fine toward
 * addresses that can never be routed over the public internet — loopback,
 * private LAN ranges (RFC 1918), and Tailscale's CGNAT device range
 * (100.64.0.0/10) — while any public hostname still requires HTTPS. This lets
 * users reach a home server at e.g. `http://192.168.1.10:8787` over a LAN or
 * VPN without a TLS cert, which is a common self-hosting setup.
 *
 * Android's Network Security Config can't express a CIDR range, so cleartext is
 * permitted platform-wide (res/xml/network_security_config.xml) and THIS check
 * enforces the real rule in the connection layer — every URL is validated
 * through it before a client is built (Android port plan §2).
 *
 * `10.0.2.2` is the Android emulator's alias for the host machine's loopback —
 * the emulator counterpart of the iOS simulator's `localhost` testing path (it
 * also falls inside 10.0.0.0/8 below, but is kept explicit for clarity).
 */
object CleartextPolicy {

    fun allowsCleartext(host: String): Boolean {
        val normalized = host.lowercase()
        if (normalized == "localhost" || normalized == "127.0.0.1" || normalized == "10.0.2.2") {
            return true
        }

        val octets = normalized.split(".").mapNotNull { it.toIntOrNull() }
        if (octets.size != 4 || octets.any { it !in 0..255 }) return false
        val (a, b, _, _) = octets

        return when {
            // RFC 1918 private ranges (home LAN / VPN).
            a == 10 -> true                       // 10.0.0.0/8
            a == 172 && b in 16..31 -> true       // 172.16.0.0/12
            a == 192 && b == 168 -> true          // 192.168.0.0/16
            // Tailscale CGNAT device range 100.64.0.0/10.
            a == 100 && b in 64..127 -> true
            else -> false
        }
    }
}
