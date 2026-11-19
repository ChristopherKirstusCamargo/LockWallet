package com.lockwallet.data.price

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import java.math.BigDecimal

class PriceTest {
    private var now = 0L
    private fun transport(map: Map<String, String>) = HttpTransport { url, _ -> (map[url] ?: throw PriceException("down")).toByteArray() }
    private val cb = "https://api.coinbase.com/v2/exchange-rates?currency=SOL"
    private val kraken = "https://api.kraken.com/0/public/Ticker?pair=SOLUSD,SOLEUR,SOLGBP,SOLCAD,SOLAUD"
    private fun cbBody(usd: String = "\"73.965\"", base: String = "SOL", extra: String = "") =
        """{"data":{"currency":"$base","rates":{"00":"1","USD":$usd,"EUR":"66.29","JPY":"11607.47344333333333091","GBP":"55.51","CNY":"485.3833675","BRL":"436.2381734999999038455","CAD":"104.307","AUD":"109.13","CHF":"61.349","INR":"7710.93","KRW":"97009.36"$extra}}}"""
    private val good = mapOf(
        cb to cbBody(),
        kraken to """{"error":[],"result":{"SOLAUD":{"c":["109.00000","2.7"]},"SOLCAD":{"c":["104.49000","0.4"]},"SOLEUR":{"c":["66.26000","15.0"]},"SOLGBP":{"c":["55.54000","0.2"]},"SOLUSD":{"a":["73.99000","163","163.000"],"c":["73.98000","0.71863476"]}}}""",
    )

    @Test fun parsesRealResponseShapesExactly() {
        val p = CoinbaseSource(transport(good)).fetch()
        assertEquals(Fiat.entries.toSet(), p.keys)
        assertEquals(BigDecimal("73.965"), p[Fiat.USD])
        assertEquals(BigDecimal("436.2381734999999038455"), p[Fiat.BRL])
        assertEquals(BigDecimal("11607.47344333333333091"), p[Fiat.JPY])
        val k = KrakenSource(transport(good)).fetch()
        assertEquals(BigDecimal("73.98000"), k[Fiat.USD])
        assertEquals(setOf(Fiat.USD, Fiat.EUR, Fiat.GBP, Fiat.CAD, Fiat.AUD), k.keys)   // never invents JPY/BRL/…
    }

    @Test fun tenWorldCurrencies() = assertEquals(listOf("USD", "EUR", "JPY", "GBP", "CNY", "BRL", "CAD", "AUD", "CHF", "INR"), Fiat.entries.map { it.name })

    /** One bad currency value is dropped, not invented; a missing/implausible USD rejects the whole quote. */
    @Test fun perCurrencyValidation() {
        val p = CoinbaseSource(transport(mapOf(cb to cbBody(extra = "").replace("\"EUR\":\"66.29\"", "\"EUR\":\"-1\"")))).fetch()
        assertNull(p[Fiat.EUR]); assertEquals(BigDecimal("73.965"), p[Fiat.USD])
        assertEquals(PriceState.Unavailable, PriceService(listOf(CoinbaseSource(transport(mapOf(cb to cbBody(usd = "\"0\""))))), { 0 }).refresh())
        assertEquals(PriceState.Unavailable, PriceService(listOf(CoinbaseSource(transport(mapOf(cb to cbBody(base = "BTC"))))), { 0 }).refresh())
    }

    @Test fun fallsBackThenGoesStaleThenNeverInvents() {
        var map = good
        val svc = PriceService(listOf(CoinbaseSource(transport(map)), KrakenSource { u, m -> transport(map).get(u, m) }), { now }, 20_000)
        assertEquals(PriceState.Unavailable, svc.state())
        assertEquals("Coinbase", (svc.refresh() as PriceState.Fresh).quote.source)
        val onlyKraken = PriceService(listOf(CoinbaseSource(transport(emptyMap())), KrakenSource(transport(good))), { now })
        assertEquals("Kraken", (onlyKraken.refresh() as PriceState.Fresh).quote.source)
        map = emptyMap()
        val down = PriceService(listOf(CoinbaseSource(transport(emptyMap())), KrakenSource(transport(emptyMap()))), { now })
        assertEquals(PriceState.Unavailable, down.refresh())
        now += 25_000
        val st = onlyKraken.refresh()
        assertTrue(st is PriceState.Fresh) // kraken still answers
        val failing = PriceService(listOf(CoinbaseSource(transport(good))), { now })
        failing.refresh()
        val stale = PriceService(listOf(object : PriceSource {
            var ok = true
            override val name = "x"
            override fun fetch(): Map<Fiat, BigDecimal> { if (!ok) throw PriceException("down"); ok = false; return mapOf(Fiat.USD to BigDecimal("1")) }
        }), { now }, 20_000)
        stale.refresh(); now += 21_000
        val s = stale.refresh() as PriceState.Stale
        assertEquals(21_000, s.ageMs)
        assertEquals(BigDecimal("1"), s.quote.prices[Fiat.USD])
    }

    @Test fun rejectsMalformedOrImplausibleData() {
        fun bad(body: String) = PriceService(listOf(CoinbaseSource(transport(mapOf(cb to body)))), { 0 }).refresh()
        assertEquals(PriceState.Unavailable, bad(cbBody(usd = "\"0\"")))
        assertEquals(PriceState.Unavailable, bad(cbBody(usd = "\"-5\"")))
        assertEquals(PriceState.Unavailable, bad(cbBody(usd = "\"1e3\"")))
        assertEquals(PriceState.Unavailable, bad(cbBody(usd = "\"99999999999\"")))
        assertEquals(PriceState.Unavailable, bad(cbBody() + " x"))
        assertEquals(PriceState.Unavailable, bad(cbBody(extra = ",\"USD\":\"74\"")))     // duplicate key
        assertEquals(PriceState.Unavailable, bad("<html>"))
        assertEquals(PriceState.Unavailable, bad("[".repeat(100)))
        assertEquals(PriceState.Unavailable, PriceService(listOf(KrakenSource(transport(mapOf(kraken to """{"error":["EQuery"],"result":{}}""")))), { 0 }).refresh())
    }

    @Test fun pollIntervalUnderTenSeconds() = assertTrue(PriceService.POLL_INTERVAL_MS < 10_000)

    @Test fun transportRefusesPlainHttp() {
        assertTrue(runCatching { HttpsTransport().get("http://api.coinbase.com/", 10) }.exceptionOrNull() is IllegalArgumentException)
    }

    /** 24 h change: real Coinbase stats shape; cached for a minute; implausible values refused; failure keeps the last real one. */
    @Test fun changeIsComputedCachedAndNeverInvented() {
        var body = """{"open":"72.98","high":"75.57","low":"72.24","last":"73.18","volume":"897186.81"}"""
        var calls = 0
        val ch = CoinbaseChange { url, _ -> calls++; check(url.endsWith("/products/SOL-USD/stats")); body.toByteArray() }
        assertEquals(BigDecimal("0.00274048"), ch.fetch())
        var t = 0L
        val svc = PriceService(listOf(object : PriceSource { override val name = "T"; override fun fetch() = mapOf(Fiat.USD to BigDecimal("73.18")) }), { t }, change = ch)
        val q1 = (svc.refresh() as PriceState.Fresh).quote
        assertEquals(BigDecimal("0.00274048"), q1.change24h)
        body = """{"open":"10","last":"30"}"""          // +200 %: implausible
        t += 1_000; svc.refresh()
        assertEquals(2, calls)                              // 1 direct + 1 via service; second refresh used the cache
        t += PriceService.CHANGE_INTERVAL_MS
        val q3 = (svc.refresh() as PriceState.Fresh).quote
        assertEquals("implausible change refused, last real value kept", BigDecimal("0.00274048"), q3.change24h)
        assertThrows(PriceException::class.java) { ch.fetch() }
        body = """{"open":"0","last":"1"}"""
        assertThrows(Exception::class.java) { ch.fetch() }
    }
}
