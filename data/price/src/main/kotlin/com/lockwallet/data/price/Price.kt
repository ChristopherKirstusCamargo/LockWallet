package com.lockwallet.data.price

import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/** Fiat currencies shown by the app. */
/** The ten most-traded world currencies (ISO 4217). Order = order shown in settings. */
enum class Fiat { USD, EUR, JPY, GBP, CNY, BRL, CAD, AUD, CHF, INR }

/** A real market quote for SOL. Amounts are exact decimals as published by the source (never binary floating point). */
data class PriceQuote(val prices: Map<Fiat, BigDecimal>, val source: String, val fetchedAtElapsedMs: Long, val change24h: BigDecimal? = null)

class PriceException(msg: String) : Exception(msg)

/** Minimal HTTPS GET. Production: [HttpsTransport]. Tests inject canned responses. */
fun interface HttpTransport {
    /** Returns the body (≤ [maxBytes]) of a 200 response, or throws. */
    fun get(url: String, maxBytes: Int): ByteArray
}

/**
 * HTTPS only, no cookies (no CookieHandler installed), no auth, fixed neutral User-Agent (the platform default
 * would reveal the device model), short timeouts, bounded body. Nothing about the user or wallet is sent.
 */
class HttpsTransport(private val timeoutMs: Int = 4_000) : HttpTransport {
    override fun get(url: String, maxBytes: Int): ByteArray {
        require(url.startsWith("https://")) { "https only" }
        val c = URL(url).openConnection() as HttpsURLConnection
        try {
            c.requestMethod = "GET"
            c.connectTimeout = timeoutMs
            c.readTimeout = timeoutMs
            c.instanceFollowRedirects = false
            c.useCaches = false
            c.setRequestProperty("User-Agent", "LockWallet")
            c.setRequestProperty("Accept", "application/json")
            if (c.responseCode != 200) throw PriceException("http ${c.responseCode}")
            c.inputStream.use { s ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(4096)
                while (true) {
                    val n = s.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > maxBytes) throw PriceException("response too large")
                }
                return out.toByteArray()
            }
        } finally {
            c.disconnect()
        }
    }
}

/** One public, keyless price source. */
interface PriceSource {
    val name: String
    fun fetch(): Map<Fiat, BigDecimal>
}

private const val MAX_BODY = 16 * 1024
private const val MAX_RATES_BODY = 64 * 1024
private val MIN_PRICE = BigDecimal("0.0001")
private val MAX_PRICE = BigDecimal("10000000")

internal fun sane(v: BigDecimal): BigDecimal {
    if (v < MIN_PRICE || v > MAX_PRICE) throw PriceException("implausible price")
    return v
}

internal fun decimal(text: Any?): BigDecimal {
    val t = when (text) { is String -> text; is MiniJson.Num -> text.text; else -> throw PriceException("not a number") }
    if (t.length > 40 || !Regex("""\d{1,12}(\.\d{1,30})?""").matches(t)) throw PriceException("bad number")
    return sane(BigDecimal(t))
}

@Suppress("UNCHECKED_CAST")
private fun obj(v: Any?): Map<String, Any?> = v as? Map<String, Any?> ?: throw PriceException("bad json")

/**
 * Coinbase public exchange rates for SOL: GET /v2/exchange-rates?currency=SOL →
 * {"data":{"currency":"SOL","rates":{"USD":"72.7","EUR":"66.29",…}}}. One request gives every supported fiat; each
 * value is validated on its own (a missing or implausible currency is simply absent, never invented).
 */
class CoinbaseSource(private val http: HttpTransport) : PriceSource {
    override val name = "Coinbase"
    override fun fetch(): Map<Fiat, BigDecimal> {
        val body = String(http.get("https://api.coinbase.com/v2/exchange-rates?currency=SOL", MAX_RATES_BODY), Charsets.UTF_8)
        val data = obj(obj(parse(body))["data"])
        if (data["currency"] != "SOL") throw PriceException("unexpected base")
        val rates = obj(data["rates"])
        val out = LinkedHashMap<Fiat, BigDecimal>()
        for (f in Fiat.entries) { val v = rates[f.name] ?: continue; out[f] = runCatching { decimal(v) }.getOrNull() ?: continue }
        if (out[Fiat.USD] == null) throw PriceException("no USD")
        return out
    }
}

/** Kraken public ticker (fallback): last trade of the SOL pairs Kraken lists (USD, EUR, GBP, CAD, AUD). */
class KrakenSource(private val http: HttpTransport) : PriceSource {
    override val name = "Kraken"
    override fun fetch(): Map<Fiat, BigDecimal> {
        val body = String(http.get("https://api.kraken.com/0/public/Ticker?pair=" + PAIRS.joinToString(",") { "SOL${it.name}" }, MAX_BODY), Charsets.UTF_8)
        val root = obj(parse(body))
        val err = root["error"] as? List<*> ?: throw PriceException("bad json")
        if (err.isNotEmpty()) throw PriceException("source error")
        val result = obj(root["result"])
        val out = LinkedHashMap<Fiat, BigDecimal>()
        for (f in PAIRS) {
            val pair = result["SOL${f.name}"]?.let { obj(it) } ?: continue
            val last = (pair["c"] as? List<*>)?.firstOrNull() ?: continue
            out[f] = runCatching { decimal(last) }.getOrNull() ?: continue
        }
        if (out[Fiat.USD] == null) throw PriceException("no USD")
        return out
    }
    companion object { val PAIRS = listOf(Fiat.USD, Fiat.EUR, Fiat.GBP, Fiat.CAD, Fiat.AUD) }
}

/**
 * 24 h change of SOL (ratio, e.g. 0.0123 = +1.23 %) from Coinbase Exchange public stats: (last − open) / open, where
 * open is the price 24 h ago. Currency-independent ratio, applied to every fiat (FX drift ignored and labelled 24 h).
 */
class CoinbaseChange(private val http: HttpTransport) {
    fun fetch(): BigDecimal {
        val o = obj(parse(String(http.get("https://api.exchange.coinbase.com/products/SOL-USD/stats", MAX_BODY), Charsets.UTF_8)))
        val open = decimal(o["open"]); val last = decimal(o["last"])
        val r = last.subtract(open).divide(open, 8, java.math.RoundingMode.HALF_EVEN)
        if (r.abs() > BigDecimal.ONE) throw PriceException("implausible change")
        return r
    }
}

private fun parse(body: String): Any? = try { MiniJson.parse(body) } catch (e: MiniJson.JsonException) { throw PriceException("bad json") }

/** What the UI shows. A value is only ever a real fetched quote; age decides FRESH vs STALE. */
sealed class PriceState {
    data object Loading : PriceState()
    data class Fresh(val quote: PriceQuote) : PriceState()
    data class Stale(val quote: PriceQuote, val ageMs: Long) : PriceState()
    data object Unavailable : PriceState()
}

/**
 * Tries sources in order (primary, then fallback) and keeps the last good quote. A quote older than
 * [staleAfterMs] is reported as STALE (still real, clearly labelled); with no quote at all: UNAVAILABLE.
 */
class PriceService(
    private val sources: List<PriceSource>,
    private val elapsed: () -> Long,
    private val staleAfterMs: Long = 20_000,
    private val change: CoinbaseChange? = null,
) {
    @Volatile private var last: PriceQuote? = null
    @Volatile private var lastChange: BigDecimal? = null
    @Volatile private var changeAt: Long = Long.MIN_VALUE / 2

    private fun changeNow(): BigDecimal? {
        val c = change ?: return null
        if (elapsed() - changeAt >= CHANGE_INTERVAL_MS) {
            changeAt = elapsed()
            lastChange = try { c.fetch() } catch (_: Exception) { lastChange }
        }
        return lastChange
    }

    /** Blocking; call off the main thread. Never throws. */
    fun refresh(): PriceState {
        for (s in sources) {
            try {
                val p = s.fetch()
                if (p[Fiat.USD] == null) continue
                last = PriceQuote(p, s.name, elapsed(), changeNow())
                return state()
            } catch (_: Exception) {
                // try next source
            }
        }
        return state()
    }

    fun state(): PriceState {
        val q = last ?: return PriceState.Unavailable
        val age = elapsed() - q.fetchedAtElapsedMs
        return if (age <= staleAfterMs) PriceState.Fresh(q) else PriceState.Stale(q, age)
    }

    companion object {
        /** Poll interval while the app is visible and unlocked (requirement: refresh in < 10 s). */
        const val POLL_INTERVAL_MS = 6_000L
        /** The 24 h change moves slowly: refreshed at most once a minute. */
        const val CHANGE_INTERVAL_MS = 60_000L

        fun default(elapsed: () -> Long): PriceService {
            val http = HttpsTransport()
            return PriceService(listOf(CoinbaseSource(http), KrakenSource(http)), elapsed, change = CoinbaseChange(http))
        }
    }
}
