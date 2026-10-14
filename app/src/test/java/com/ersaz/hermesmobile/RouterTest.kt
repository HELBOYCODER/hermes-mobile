package com.ersaz.hermesmobile

import com.ersaz.hermesmobile.core.ModelProfile
import com.ersaz.hermesmobile.router.RouterApi
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class RouterTest {
    @Test fun openCodeFreeLaneExcludesZenAndOtherProviders() {
        listOf("oc/big-pickle", "oc/union-alpha", "oc/jev-1.13-free", "opencode/muse-spark-free")
            .forEach { assertTrue(it, RouterApi.isOpenCodeFree(it)) }
        listOf("ocz/gpt-5-free", "opencode-zen/union-alpha", "kgw/model:free", "oc/", "oc")
            .forEach { assertFalse(it, RouterApi.isOpenCodeFree(it)) }
        assertEquals("opencode", RouterApi.OPENCODE_FREE_PROVIDER)
    }

    @Test fun panelLoginReturnsThePasswordThatActuallyWorked() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setHeader("Set-Cookie", "auth_token=legacy; Path=/"))
        server.start()
        try {
            val panel = RouterApi(OkHttpClient(), server.url("").toString().trimEnd('/')).loginPanel("old-saved")!!
            assertEquals("123456", panel.password)
            assertEquals("auth_token=legacy", panel.cookie)
            assertTrue(server.takeRequest().body.readUtf8().contains("old-saved"))
            assertTrue(server.takeRequest().body.readUtf8().contains("123456"))
        } finally { server.shutdown() }
    }

    @Test fun openCodeFreeCombinesLiveCatalogWithBundledLaneAndExcludesZen() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"data":[{"id":"big-pickle"},{"id":"new-free"}]}"""))
        server.enqueue(MockResponse().setBody("""{"data":[{"id":"oc/union-alpha"},{"id":"ocz/paid-free"},{"id":"other/free"}]}"""))
        server.start()
        try {
            val catalog = RouterApi(OkHttpClient(), server.url("").toString().trimEnd('/')).openCodeFreeModels("auth_token=x", "key")
            assertEquals(listOf("oc/big-pickle", "oc/new-free", "oc/union-alpha"), catalog.models)
            assertTrue(catalog.live)
            assertEquals("opencode-free", server.takeRequest().requestUrl!!.queryParameter("type"))
        } finally { server.shutdown() }
    }

    @Test fun unavailableLiveCatalogFallsBackToOpenCodeFreeOnly() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().setBody("""{"data":[{"id":"oc/union-alpha"},{"id":"ocz/zen-free"}]}"""))
        server.start()
        try {
            val catalog = RouterApi(OkHttpClient(), server.url("").toString().trimEnd('/')).openCodeFreeModels("auth_token=x", "key")
            assertEquals(listOf("oc/union-alpha"), catalog.models)
            assertFalse(catalog.live)
        } finally { server.shutdown() }
    }

    @Test
    fun freeModelDetection() {
        listOf("oc/jev-1.13-free", "kgw/nvidia/nemotron-3-super-120b-a12b:free", "bzl/auto:free", "tokenrouter/z-ai/glm-5.3-free", "kgw/kilo-auto/free")
            .forEach { assertTrue(it, RouterApi.isFree(it)) }
        listOf("kr/claude-opus-5.5", "openai/gpt-5", "ocz/freedom-model", "gh/carefree").forEach { assertFalse(it, RouterApi.isFree(it)) }
    }

    @Test
    fun loginKeyAndModelsAgainstMockServer() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setHeader("Set-Cookie", "auth_token=abc; Path=/; HttpOnly").setBody("""{"success":true}"""))
        server.enqueue(MockResponse().setBody("""{"key":"sk-test","name":"hermes-mobile"}"""))
        server.enqueue(MockResponse().setBody("""{"connections":[{"provider":"opencode-zen"}]}"""))
        server.enqueue(MockResponse().setBody("""{"data":[{"id":"a/free-x"},{"id":"b/paid"}]}"""))
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"error":{"message":"FreeTierError"}}"""))
        server.start()
        val api = RouterApi(OkHttpClient(), server.url("").toString().trimEnd('/'))
        val cookie = api.login("pw")
        assertEquals("auth_token=abc", cookie)
        assertEquals("sk-test", api.createKey(cookie!!, "n"))
        assertEquals(setOf("opencode-zen"), api.connectedProviders(cookie))
        assertEquals(listOf("a/free-x", "b/paid"), api.models("sk-test"))
        assertEquals("FreeTierError", api.probe("sk-test", "a/free-x"))
        server.shutdown()
    }

    @Test
    fun modelProfileJsonRoundTrip() {
        val p = ModelProfile("9Router", "9router", "http://127.0.0.1:20128/v1", "k", "oc/x-free", true, "9router")
        assertEquals(p, ModelProfile.fromJson(p.toJson()))
    }

    /** Runs only when a real 9Router is listening locally: ROUTER_LIVE=1 ./gradlew testReleaseUnitTest */
    @Test
    fun liveRouterFlow() = runBlocking {
        assumeTrue(System.getenv("ROUTER_LIVE") == "1")
        val api = RouterApi(OkHttpClient(), "http://127.0.0.1:20128")
        assertTrue(api.isUp())
        val cookie = api.login("123456")
        assertNotNull(cookie)
        val key = api.createKey(cookie!!, "unit-test")
        assertNotNull(key)
        val models = api.models(key!!)
        println("LIVE models=${models.size} free=${models.count { RouterApi.isFree(it) }}")
        assertTrue(models.count { RouterApi.isFree(it) } > 0)
    }
}
