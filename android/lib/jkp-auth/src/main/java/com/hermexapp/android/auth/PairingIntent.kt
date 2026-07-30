package com.hermexapp.android.auth

import com.hermexapp.android.network.CleartextPolicy
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Parsed result of a URL that came out of either a QR code (desktop's
 * `python -m jkp pair`) or a paste from the clipboard.
 *
 * The desktop side's `mobile_pairing.build_pairing_url()` encodes:
 *   {endpoint}/v1/pair/connect#{query}
 *   where query = urlencode({"pair_id": ..., "token": ...})
 *
 * It puts the credentials in the fragment so they never appear in HTTP
 * request lines or server access logs when the URL is opened in a
 * browser. Both query and fragment are accepted here — the QR scanner
 * reads the whole URL, including any fragment.
 *
 * Three outcomes:
 *   - [CompletePairing]    → server URL + pair_id + token (call POST /v1/pair/complete)
 *   - [ServerUrlOnly]      → just the URL (paste-and-go, no auto-pair)
 *   - [Invalid]            → malformed, unsupported scheme, or no host
 */
sealed class PairingIntent {
    data class CompletePairing(
        val serverUrl: HttpUrl,
        val pairId: String,
        val token: String,
    ) : PairingIntent()

    data class ServerUrlOnly(val serverUrl: HttpUrl) : PairingIntent()

    data class Invalid(val reason: String) : PairingIntent()
}

object PairingIntentParser {

    private const val PAIR_PATH = "/v1/pair/connect"

    /**
     * Parse a raw URL string. Tolerates extra whitespace and missing
     * scheme (defaults to https). Returns [PairingIntent.Invalid] for
     * empty input, non-HTTP schemes, hosts that can't be reached, or
     * cleartext-blocked targets outside the allowlist (mirrors the
     * rule that [ServerUrlNormalizer] enforces for the typed flow).
     */
    fun parse(raw: String): PairingIntent {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return PairingIntent.Invalid("Empty URL")

        val withScheme = if ("://" in trimmed) trimmed else "https://$trimmed"
        val parsed = withScheme.toHttpUrlOrNull()
            ?: return PairingIntent.Invalid("Could not parse URL")

        if (parsed.scheme !in setOf("http", "https")) {
            return PairingIntent.Invalid("Unsupported scheme: ${parsed.scheme}")
        }

        val normalized = parsed.newBuilder()
            .encodedPath("/")
            .query(null)
            .fragment(null)
            .build()

        if (normalized.scheme == "http" &&
            !CleartextPolicy.allowsCleartext(normalized.host)
        ) {
            return PairingIntent.Invalid("Cleartext blocked for ${normalized.host}")
        }

        // Pair credentials can live in EITHER the query string (when
        // the URL is opened via deep-link / scanner) or the fragment
        // (when the URL is opened in a browser, per the desktop's
        // build_pairing_url). We accept both, with query taking
        // precedence (it's the more explicit intent).
        val credentials = extractCredentials(parsed)
            ?: extractFromFragment(parsed.fragment)

        return if (credentials != null) {
            PairingIntent.CompletePairing(
                serverUrl = normalized,
                pairId = credentials.pairId,
                token = credentials.token,
            )
        } else {
            PairingIntent.ServerUrlOnly(normalized)
        }
    }

    private data class PairCredentials(val pairId: String, val token: String)

    private fun extractCredentials(url: HttpUrl): PairCredentials? {
        if (!url.encodedPath.endsWith(PAIR_PATH)) return null
        val pairId = url.queryParameter("pair_id")?.takeIf { it.isNotBlank() }
            ?: return null
        val token = url.queryParameter("token")?.takeIf { it.isNotBlank() }
            ?: return null
        return PairCredentials(pairId, token)
    }

    private fun extractFromFragment(fragment: String?): PairCredentials? {
        if (fragment.isNullOrBlank()) return null
        // The fragment is a URL-encoded query string.
        val params = fragment.split('&')
            .mapNotNull { param ->
                val parts = param.split('=', limit = 2)
                if (parts.size != 2) null
                else parts[0] to java.net.URLDecoder.decode(
                    parts[1].replace('+', ' '),
                    "UTF-8",
                )
            }
            .toMap()
        val pairId = params["pair_id"]?.takeIf { it.isNotBlank() } ?: return null
        val token = params["token"]?.takeIf { it.isNotBlank() } ?: return null
        return PairCredentials(pairId, token)
    }
}