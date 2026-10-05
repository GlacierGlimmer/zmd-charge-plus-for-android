package com.glacierglimmer.endfieldchargeplus.deepseek

import com.glacierglimmer.endfieldchargeplus.diagnostics.AppLog
import com.glacierglimmer.endfieldchargeplus.network.HttpResponse
import com.glacierglimmer.endfieldchargeplus.network.HttpTransport
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * DeepSeek response parsing plus the two security guarantees: no fabricated balance and no API key
 * anywhere in the exported diagnostics log.
 */
class DefaultDeepSeekClientTest {

    private val successBody =
        """{"is_available":true,"balance_infos":[{"currency":"CNY","total_balance":"12.50",""" +
            """"granted_balance":"2.50","topped_up_balance":"10.00"}]}"""

    @Before
    fun resetLog() {
        AppLog.clear()
        AppLog.clearSecrets()
    }

    // ---- parsing ----------------------------------------------------------------------------

    @Test
    fun `parses the documented success response`() {
        val balance = DefaultDeepSeekClient.parseResponse(200, successBody)
        assertNull(balance.error)
        assertTrue(balance.available)
        assertEquals("CNY", balance.currency)
        assertEquals(12.5, balance.totalBalance!!, 1e-9)
        assertEquals(2.5, balance.grantedBalance!!, 1e-9)
        assertEquals(10.0, balance.toppedUpBalance!!, 1e-9)
    }

    @Test
    fun `accepts numeric json values as well as numeric strings`() {
        val body = """{"is_available":true,"balance_infos":[{"currency":"CNY","total_balance":8,""" +
            """"granted_balance":0,"topped_up_balance":8}]}"""
        val balance = DefaultDeepSeekClient.parseResponse(200, body)
        assertEquals(8.0, balance.totalBalance!!, 1e-9)
        assertEquals(0.0, balance.grantedBalance!!, 1e-9)
    }

    @Test
    fun `is_available false is a valid answer not a fabricated balance`() {
        val body = """{"is_available":false,"balance_infos":[{"currency":"CNY","total_balance":"0.00",""" +
            """"granted_balance":"0.00","topped_up_balance":"0.00"}]}"""
        val balance = DefaultDeepSeekClient.parseResponse(200, body)
        assertNull(balance.error)
        assertFalse(balance.available)
        assertEquals(0.0, balance.totalBalance!!, 1e-9)
    }

    @Test
    fun `a cny entry is preferred over other currencies`() {
        val body = """{"is_available":true,"balance_infos":[""" +
            """{"currency":"USD","total_balance":"1.00","granted_balance":"0.00","topped_up_balance":"1.00"},""" +
            """{"currency":"CNY","total_balance":"12.50","granted_balance":"2.50","topped_up_balance":"10.00"}]}"""
        val balance = DefaultDeepSeekClient.parseResponse(200, body)
        assertEquals("CNY", balance.currency)
        assertEquals(12.5, balance.totalBalance!!, 1e-9)
    }

    @Test
    fun `an unknown currency still reports its own currency`() {
        val body = """{"is_available":true,"balance_infos":[""" +
            """{"currency":"HKD","total_balance":"3.00","granted_balance":"1.00","topped_up_balance":"2.00"}]}"""
        val balance = DefaultDeepSeekClient.parseResponse(200, body)
        assertEquals("HKD", balance.currency)
        assertEquals(3.0, balance.totalBalance!!, 1e-9)
    }

    @Test
    fun `malformed json becomes a failure`() {
        val balance = DefaultDeepSeekClient.parseResponse(200, "{not json")
        assertEquals(DefaultDeepSeekClient.ERROR_INVALID_JSON, balance.error)
        assertFalse(balance.available)
        assertNull(balance.totalBalance)
    }

    @Test
    fun `a json array instead of an object becomes a failure`() {
        val balance = DefaultDeepSeekClient.parseResponse(200, "[]")
        assertEquals(DefaultDeepSeekClient.ERROR_INVALID_JSON, balance.error)
    }

    @Test
    fun `http errors become failures with the status code`() {
        assertEquals("http_401", DefaultDeepSeekClient.parseResponse(401, successBody).error)
        assertEquals("http_500", DefaultDeepSeekClient.parseResponse(500, "").error)
        assertEquals("http_429", DefaultDeepSeekClient.parseResponse(429, successBody).error)
    }

    @Test
    fun `an available account without balance entries is a failure`() {
        val balance = DefaultDeepSeekClient.parseResponse(200, """{"is_available":true,"balance_infos":[]}""")
        assertEquals(DefaultDeepSeekClient.ERROR_MISSING_BALANCE, balance.error)
    }

    @Test
    fun `an unparseable amount is a failure rather than a fake zero`() {
        val body = """{"is_available":true,"balance_infos":[{"currency":"CNY","total_balance":"abc",""" +
            """"granted_balance":"2.50","topped_up_balance":"10.00"}]}"""
        val balance = DefaultDeepSeekClient.parseResponse(200, body)
        assertEquals(DefaultDeepSeekClient.ERROR_INVALID_BALANCE, balance.error)
    }

    // ---- request behaviour ------------------------------------------------------------------

    @Test
    fun `a blank key is rejected without any request`() = runBlocking {
        var called = false
        val transport = object : HttpTransport {
            override suspend fun get(
                url: String,
                headers: Map<String, String>,
                timeoutMs: Int,
                maxBytes: Int,
            ): HttpResponse {
                called = true
                return HttpResponse(200, successBody)
            }
        }
        val balance = DefaultDeepSeekClient(transport).fetchBalance("", "https://api.deepseek.com")
        assertEquals(DefaultDeepSeekClient.ERROR_NO_KEY, balance.error)
        assertFalse(called)
    }

    @Test
    fun `cleartext http to a remote host is rejected before any request`() = runBlocking {
        var called = false
        val transport = object : HttpTransport {
            override suspend fun get(
                url: String,
                headers: Map<String, String>,
                timeoutMs: Int,
                maxBytes: Int,
            ): HttpResponse {
                called = true
                return HttpResponse(200, successBody)
            }
        }
        val balance = DefaultDeepSeekClient(transport)
            .fetchBalance("sk-test-1234567890", "http://api.deepseek.com")
        assertEquals("cleartext_http_rejected", balance.error)
        assertFalse(called)
    }

    @Test
    fun `the authorization header carries the key and the url is the balance endpoint`() = runBlocking {
        var seenUrl = ""
        var seenHeaders: Map<String, String> = emptyMap()
        val transport = object : HttpTransport {
            override suspend fun get(
                url: String,
                headers: Map<String, String>,
                timeoutMs: Int,
                maxBytes: Int,
            ): HttpResponse {
                seenUrl = url
                seenHeaders = headers
                return HttpResponse(200, successBody)
            }
        }
        val balance = DefaultDeepSeekClient(transport).fetchBalance("sk-test-1234567890", "https://api.deepseek.com/")
        assertTrue(balance.available)
        assertEquals("https://api.deepseek.com/user/balance", seenUrl)
        assertEquals("Bearer sk-test-1234567890", seenHeaders["Authorization"])
        assertEquals("application/json", seenHeaders["Accept"])
    }

    @Test
    fun `a transport failure becomes a non-secret failure message`() = runBlocking {
        val key = "sk-secret-9876543210abcdef"
        val transport = object : HttpTransport {
            override suspend fun get(
                url: String,
                headers: Map<String, String>,
                timeoutMs: Int,
                maxBytes: Int,
            ): HttpResponse = throw IOException("connection reset")
        }
        val balance = DefaultDeepSeekClient(transport).fetchBalance(key, "https://api.deepseek.com")
        assertEquals(DefaultDeepSeekClient.ERROR_REQUEST_FAILED, balance.error)
        assertFalse(balance.error!!.contains(key))
        assertFalse(AppLog.exportText().contains(key))
    }

    // ---- secret redaction -------------------------------------------------------------------

    @Test
    fun `the key is registered for redaction and never reaches the exported log`() = runBlocking {
        val key = "sk-abcdef1234567890"
        val transport = object : HttpTransport {
            override suspend fun get(
                url: String,
                headers: Map<String, String>,
                timeoutMs: Int,
                maxBytes: Int,
            ): HttpResponse = HttpResponse(200, successBody)
        }
        val balance = DefaultDeepSeekClient(transport).fetchBalance(key, "https://api.deepseek.com")
        assertTrue(balance.available)

        assertFalse(
            "the API key must never appear in the exported log",
            AppLog.exportText().contains(key),
        )
        assertFalse(AppLog.redact("key=$key").contains(key))
        assertEquals("****7890", AppLog.mask(key))

        // Even a caller that logs the key by accident is protected by registerSecret.
        AppLog.i("test", "accidental $key")
        assertFalse(AppLog.exportText().contains(key))
    }

    @Test
    fun `failure codes are stable short tokens without secrets`() {
        for (code in listOf(
            DefaultDeepSeekClient.ERROR_NO_KEY,
            DefaultDeepSeekClient.ERROR_REQUEST_FAILED,
            DefaultDeepSeekClient.ERROR_INVALID_JSON,
            DefaultDeepSeekClient.ERROR_MISSING_BALANCE,
            DefaultDeepSeekClient.ERROR_INVALID_BALANCE,
        )) {
            assertNotNull(code)
            assertFalse(code.startsWith("sk-"))
        }
    }
}
