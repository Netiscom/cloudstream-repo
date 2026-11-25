package com.kodi.cloudstream

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.base64Encode
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * VoE extractor with support for the "Confirm you're human" altcha gate.
 *
 * AresHD redirects its VoE links to rotating mirrors (voe.sx, caseyemavoid.com, morencius.com...)
 * which answer with an altcha PBKDF2 proof-of-work challenge before serving the player config.
 * The stock Cloudstream extractor only understands the config script, so we solve the challenge
 * here and reuse the same obfuscated-config decoding.
 */
open class VoeAltcha : ExtractorApi() {
    override var name = "Voe"
    override var mainUrl = "https://voe.sx"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val first = runCatching { app.get(url, referer = referer) }.getOrNull() ?: return
        var pageUrl = url
        var html = first.text
        var doc = first.document
        var cookies = first.cookies

        // voe.sx answers with a javascript redirect to the current mirror
        val redirect = REDIRECT_REGEX.find(html)?.groupValues?.getOrNull(1)
        if (redirect != null && redirect != pageUrl && redirect.startsWith("http")) {
            val response = runCatching { app.get(redirect, referer = url, cookies = cookies) }.getOrNull() ?: return
            pageUrl = redirect
            html = response.text
            doc = response.document
            cookies = cookies + response.cookies
        }

        if (isGate(html)) {
            val verified = solveGate(pageUrl, cookies, doc) ?: return
            html = verified
            doc = Jsoup.parse(verified, pageUrl)
        }

        val config = extractConfig(doc) ?: return

        config.source?.takeIf { it.startsWith("http") }?.let { source ->
            callback(link(source, pageUrl, ExtractorLinkType.M3U8, name))
        }
        config.directAccessUrl?.takeIf { it.startsWith("http") }?.let { direct ->
            callback(link(direct, pageUrl, INFER_TYPE, "$name MP4"))
        }
    }

    private suspend fun solveGate(
        pageUrl: String,
        cookies: Map<String, String>,
        doc: Document
    ): String? {
        val form = doc.selectFirst("form.access-form") ?: doc.selectFirst("form") ?: return null
        val action = form.absUrl("action").ifBlank { pageUrl }
        val token = form.selectFirst("input[name=_token]")?.attr("value").orEmpty()
        val widget = doc.selectFirst("altcha-widget") ?: return null
        val challengeUrl = widget.absUrl("challenge").ifBlank { widget.attr("challenge") }
        if (action.isBlank() || challengeUrl.isBlank()) return null

        val challengeResponse = runCatching {
            app.get(challengeUrl, referer = pageUrl, cookies = cookies)
        }.getOrNull() ?: return null
        if (!challengeResponse.isSuccessful) return null

        val challengeJson = challengeResponse.text.trim()
        if (!challengeJson.startsWith("{")) return null
        val challenge = runCatching { parseJson<AltchaChallenge>(challengeJson) }.getOrNull() ?: return null
        val solution = solveChallenge(challenge) ?: return null

        // Keep the raw challenge JSON so no field sent by the server is lost
        val payloadJson = """{"challenge":$challengeJson,"solution":${solution.toJson()}}"""
        val payload = base64Encode(payloadJson.toByteArray(Charsets.UTF_8))

        val verified = runCatching {
            app.post(
                action,
                data = mapOf("_token" to token, "access" to "0", "altcha" to payload),
                referer = pageUrl,
                cookies = cookies + challengeResponse.cookies,
                headers = mapOf("Origin" to originOf(pageUrl)),
            )
        }.getOrNull() ?: return null

        return verified.text.takeIf { it.isNotBlank() && !isGate(it) }
    }

    private fun solveChallenge(challenge: AltchaChallenge): AltchaSolution? {
        val parameters = challenge.parameters ?: return null
        val algorithm = parameters.algorithm?.uppercase().orEmpty()
        if (algorithm.isNotEmpty() &&
            algorithm != "PBKDF2/SHA-256" &&
            algorithm != "SHA-256" &&
            algorithm != "PBKDF2"
        ) {
            return null
        }

        val expiresAt = parameters.expiresAt ?: 0L
        if (expiresAt > 0 && expiresAt < System.currentTimeMillis() / 1000) return null

        val nonce = hexToBytes(parameters.nonce) ?: return null
        val salt = hexToBytes(parameters.salt) ?: return null
        val iterations = (parameters.cost ?: 1).coerceIn(1, MAX_ITERATIONS)
        val keyLength = (parameters.keyLength ?: 32).coerceIn(1, 64)
        val prefix = parameters.keyPrefix?.lowercase().orEmpty()
        if (prefix.isEmpty() || prefix.length > MAX_PREFIX_LENGTH) return null
        if (!prefix.all { it in '0'..'9' || it in 'a'..'f' }) return null

        val startedAt = System.currentTimeMillis()
        val deadline = startedAt + SOLVE_TIME_BUDGET_MS
        val workers = minOf(MAX_WORKERS, Runtime.getRuntime().availableProcessors().coerceAtLeast(1))
        val found = AtomicReference<AltchaSolution?>(null)

        // Each worker walks a slice of the counter space; PBKDF2 costs dominate, so spreading
        // the search over cores keeps the gate under a few seconds on typical devices.
        fun attempt(worker: Int) {
            val password = ByteArray(nonce.size + 4)
            nonce.copyInto(password)
            var counter = worker
            while (counter < MAX_ATTEMPTS && found.get() == null) {
                if (System.currentTimeMillis() > deadline) return
                password[nonce.size] = (counter ushr 24).toByte()
                password[nonce.size + 1] = (counter ushr 16).toByte()
                password[nonce.size + 2] = (counter ushr 8).toByte()
                password[nonce.size + 3] = counter.toByte()

                val derived = pbkdf2Sha256(password, salt, iterations, keyLength) ?: return
                val hex = derived.toHex()
                if (hex.startsWith(prefix)) {
                    found.compareAndSet(
                        null,
                        AltchaSolution(
                            counter = counter,
                            derivedKey = hex,
                            time = System.currentTimeMillis() - startedAt,
                        )
                    )
                    return
                }
                counter += workers
            }
        }

        val executor = Executors.newFixedThreadPool(workers) { runnable ->
            Thread(runnable, "voe-altcha").apply { isDaemon = true }
        }
        try {
            repeat(workers) { worker -> executor.execute { attempt(worker) } }
            executor.shutdown()
            executor.awaitTermination(SOLVE_TIME_BUDGET_MS + 1000, TimeUnit.MILLISECONDS)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            executor.shutdownNow()
        }

        return found.get()
    }

    private fun pbkdf2Sha256(
        password: ByteArray,
        salt: ByteArray,
        iterations: Int,
        keyLength: Int
    ): ByteArray? = runCatching {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(password, "HmacSHA256"))
        val blocks = (keyLength + 31) / 32
        val output = ByteArray(blocks * 32)

        for (block in 1..blocks) {
            val initial = ByteArray(salt.size + 4)
            salt.copyInto(initial)
            initial[salt.size] = (block ushr 24).toByte()
            initial[salt.size + 1] = (block ushr 16).toByte()
            initial[salt.size + 2] = (block ushr 8).toByte()
            initial[salt.size + 3] = block.toByte()

            var unit = mac.doFinal(initial)
            val accumulated = unit.copyOf()
            for (iteration in 1 until iterations) {
                unit = mac.doFinal(unit)
                for (index in accumulated.indices) {
                    accumulated[index] = (accumulated[index].toInt() xor unit[index].toInt()).toByte()
                }
            }
            accumulated.copyInto(output, (block - 1) * 32)
        }

        output.copyOf(keyLength)
    }.getOrNull()

    private fun extractConfig(doc: Document): VoeConfig? {
        for (script in doc.select("script[type=application/json]")) {
            val raw = script.data().trim()
            if (raw.isEmpty()) continue
            val encoded = if (raw.startsWith("[\"") && raw.endsWith("\"]")) {
                raw.substring(2, raw.length - 2)
            } else {
                raw
            }
            val config = decryptConfig(encoded)
            if (config?.source != null || config?.directAccessUrl != null) return config
        }
        return null
    }

    private fun decryptConfig(encoded: String): VoeConfig? = runCatching {
        var text = rot13(encoded.trim())
        for (junk in VOE_JUNK) text = text.replace(junk, "_")
        text = text.replace("_", "")
        val outer = base64Decode(text)
        val shifted = outer.map { (it.code - 3).toChar() }.joinToString("")
        val inner = base64Decode(shifted.reversed())
        parseJson<VoeConfig>(inner)
    }.getOrNull()

    private suspend fun link(
        url: String,
        referer: String,
        type: ExtractorLinkType?,
        label: String
    ): ExtractorLink = newExtractorLink(
        source = name,
        name = label,
        url = url,
        type = type,
    ) {
        this.referer = referer
        this.quality = Qualities.Unknown.value
    }

    private fun isGate(html: String): Boolean =
        html.contains("<altcha-widget", ignoreCase = true) ||
            html.contains("Confirm you're human", ignoreCase = true) ||
            html.contains("Confirm you’re human", ignoreCase = true)

    private fun originOf(url: String): String =
        Regex("^(https?://[^/]+)").find(url)?.groupValues?.getOrNull(1).orEmpty()

    private fun rot13(input: String): String = input.map { char ->
        when (char) {
            in 'A'..'Z' -> ((char - 'A' + 13) % 26 + 'A'.code).toChar()
            in 'a'..'z' -> ((char - 'a' + 13) % 26 + 'a'.code).toChar()
            else -> char
        }
    }.joinToString("")

    private fun hexToBytes(value: String?): ByteArray? {
        val text = value?.trim()?.removePrefix("0x")?.removePrefix("0X").orEmpty()
        if (text.isEmpty() || text.length % 2 != 0) return null
        if (!text.all { it in '0'..'9' || it.lowercaseChar() in 'a'..'f' }) return null
        return ByteArray(text.length / 2) { index ->
            text.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private data class AltchaChallenge(
        @JsonProperty("parameters") val parameters: AltchaParameters? = null,
        @JsonProperty("signature") val signature: String? = null,
    )

    private data class AltchaParameters(
        @JsonProperty("algorithm") val algorithm: String? = null,
        @JsonProperty("nonce") val nonce: String? = null,
        @JsonProperty("salt") val salt: String? = null,
        @JsonProperty("cost") val cost: Int? = null,
        @JsonProperty("keyLength") val keyLength: Int? = null,
        @JsonProperty("keyPrefix") val keyPrefix: String? = null,
        @JsonProperty("expiresAt") val expiresAt: Long? = null,
    )

    private data class AltchaSolution(
        @JsonProperty("counter") val counter: Int,
        @JsonProperty("derivedKey") val derivedKey: String,
        @JsonProperty("time") val time: Long,
    )

    private data class VoeConfig(
        @JsonProperty("source") val source: String? = null,
        @JsonProperty("direct_access_url") val directAccessUrl: String? = null,
    )

    companion object {
        private const val MAX_ITERATIONS = 50_000
        private const val MAX_ATTEMPTS = 4096
        private const val MAX_PREFIX_LENGTH = 4
        private const val MAX_WORKERS = 4
        private const val SOLVE_TIME_BUDGET_MS = 20_000L
        private val VOE_JUNK = listOf("@$", "^^", "~@", "%?", "*~", "!!", "#&")
        private val REDIRECT_REGEX = Regex("""window\.location\.href\s*=\s*'([^']+)'""")
    }
}

class VoeAltchaCasey : VoeAltcha() {
    override var mainUrl = "https://caseyseemavoid.com"
}

class VoeAltchaMorencius : VoeAltcha() {
    override var mainUrl = "https://morencius.com"
}
