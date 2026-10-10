package com.glacierglimmer.endfieldchargeplus.network
import org.junit.Assert.*
import org.junit.Test

class HttpRedirectHeadersTest {
    @Test fun `same-origin redirect retains authentication`() {
        val headers=mapOf("Authorization" to "test-token", "Accept" to "application/json")
        assertEquals(headers,HttpRedirectHeaders.forHop("https://api.example/a","https://api.example:443/b",headers))
    }
    @Test fun `cross-origin redirect strips authentication cookies and custom secret headers`() {
        val headers=mapOf("Authorization" to "test-token", "Cookie" to "test-cookie", "X-Private-Token" to "test-secret", "Accept" to "application/json")
        assertEquals(mapOf("Accept" to "application/json"),HttpRedirectHeaders.forHop("https://api.example/a","https://cdn.example/b",headers))
        assertEquals(mapOf("Accept" to "application/json"),HttpRedirectHeaders.forHop("https://api.example/a","https://api.example:8443/b",headers))
    }
}
