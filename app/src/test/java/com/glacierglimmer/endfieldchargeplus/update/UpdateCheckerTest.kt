package com.glacierglimmer.endfieldchargeplus.update

import com.glacierglimmer.endfieldchargeplus.network.HttpResponse
import com.glacierglimmer.endfieldchargeplus.network.HttpTransport
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

@OptIn(ExperimentalCoroutinesApi::class)
class UpdateCheckerTest {
    @Test fun `uses Android release API with PC headers and ignores local debug suffix`() = runTest {
        val fake = FakeTransport(HttpResponse(200, """{"tag_name":"v0.2.0"}"""))
        val result = GitHubUpdateClient(fake).check("0.1.0-debug-r4")
        assertEquals(UpdateStatus.UPDATE_AVAILABLE, result.status)
        assertEquals("0.1.0", result.currentVersion)
        assertTrue(fake.urls.single().endsWith("zmd-charge-plus-for-android/releases/latest"))
        assertEquals("application/vnd.github+json", fake.headers["Accept"])
        assertEquals("EndfieldChargePlusAndroid/0.1.0", fake.headers["User-Agent"])
    }
    @Test fun `missing release falls back to first repository tag`() = runTest {
        val fake = FakeTransport(HttpResponse(404, ""), HttpResponse(200, """[{"name":"V0.1.1+build.5"}]"""))
        assertEquals("0.1.1", GitHubUpdateClient(fake).check("0.1.0").latestVersion)
        assertTrue(fake.urls.last().endsWith("tags?per_page=1"))
    }
    @Test fun `null release tag and empty tag list report no remote version`() = runTest {
        val fake = FakeTransport(HttpResponse(200, """{"tag_name":null}"""), HttpResponse(200, "[]"))
        assertEquals(UpdateStatus.NO_REMOTE_VERSION, GitHubUpdateClient(fake).check("0.1.0").status)
    }
    @Test fun `numeric comparisons match PC Version including missing build and revision`() {
        assertEquals(UpdateStatus.UPDATE_AVAILABLE, UpdateVersions.compare("v0.9.0", "0.10.0").status)
        assertEquals(UpdateStatus.UP_TO_DATE, UpdateVersions.compare("0.1.0-debug-r4", "v0.1.0+7").status)
        assertEquals(UpdateStatus.LOCAL_NEWER, UpdateVersions.compare("0.2.0", "0.1.9").status)
        assertEquals(UpdateStatus.UNCOMPARABLE, UpdateVersions.compare("0.1.0", "nightly").status)
        assertEquals(UpdateStatus.UPDATE_AVAILABLE, UpdateVersions.compare("1.2", "1.2.0").status)
        assertEquals(UpdateStatus.UPDATE_AVAILABLE, UpdateVersions.compare("1.2.0", "1.2.0.1").status)
    }
    @Test fun `failed and timed out checks cannot report an update or success`() = runTest {
        val failed = object : HttpTransport { override suspend fun get(url: String, headers: Map<String,String>, timeoutMs: Int, maxBytes: Int): HttpResponse = throw IOException("offline") }
        val timeout = object : HttpTransport { override suspend fun get(url: String, headers: Map<String,String>, timeoutMs: Int, maxBytes: Int): HttpResponse = throw SocketTimeoutException() }
        assertEquals(UpdateStatus.NETWORK_ERROR, GitHubUpdateClient(failed).check("0.1.0").status)
        assertEquals(UpdateStatus.TIMEOUT, GitHubUpdateClient(timeout).check("0.1.0").status)
    }
    @Test fun `startup is checked once and manual retries do not repeat the same reminder`() = runTest {
        var requests=0; val notifications=mutableListOf<UpdateResult>()
        val checker=UpdateChecker(backgroundScope,"0.1.0",{ requests++; UpdateVersions.compare(it,"0.2.0") },notifications::add)
        repeat(20) { checker.checkStartup() }; runCurrent()
        assertEquals(1,requests); assertEquals(1,notifications.size)
        assertEquals("0.2.0",checker.state.value.promptVersion)
        checker.dismissPrompt(); checker.checkManually(); runCurrent()
        assertEquals(2,requests); assertEquals(1,notifications.size)
        assertNull(checker.state.value.promptVersion)
    }
    @Test fun `concurrent manual actions share the in-flight check`() = runTest {
        var requests=0
        val checker=UpdateChecker(backgroundScope,"0.1.0",{ requests++; delay(100); UpdateVersions.compare(it,"0.1.0") },{})
        repeat(20) { checker.checkManually() }; runCurrent()
        assertTrue(checker.state.value.checking)
        advanceTimeBy(100); runCurrent()
        assertEquals(1,requests); assertFalse(checker.state.value.checking)
    }
    private class FakeTransport(vararg responses: HttpResponse): HttpTransport {
        val urls=mutableListOf<String>(); var headers=emptyMap<String,String>()
        val responses=responses.toList()
        override suspend fun get(url:String,headers:Map<String,String>,timeoutMs:Int,maxBytes:Int):HttpResponse {
            this.headers=headers; urls+=url; assertEquals(10_000,timeoutMs)
            return responses[urls.lastIndex]
        }
    }
}
