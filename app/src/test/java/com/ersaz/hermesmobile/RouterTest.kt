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
