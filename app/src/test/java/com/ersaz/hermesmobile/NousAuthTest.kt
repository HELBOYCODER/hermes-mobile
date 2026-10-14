package com.ersaz.hermesmobile

import com.ersaz.hermesmobile.agent.Message
import com.ersaz.hermesmobile.auth.NousAuth
import com.ersaz.hermesmobile.auth.NousAuthException
import com.ersaz.hermesmobile.auth.NousPortal
import com.ersaz.hermesmobile.auth.isEntitlementError
import com.ersaz.hermesmobile.core.NousTokenStore
import com.ersaz.hermesmobile.llm.LlmClient
import com.ersaz.hermesmobile.llm.LlmException
import com.ersaz.hermesmobile.llm.ModelCatalog
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Base64

private class FakeStore : NousTokenStore {
    override var authMode = "key"
    override var nousAccessToken = ""
    override var nousRefreshToken = ""
    override var nousExpiresAt = 0L
    override var nousScope = ""
    override var nousAccount = ""
}

private fun jwt(vararg claims: Pair<String, Any>): String {
    val enc = Base64.getUrlEncoder().withoutPadding()
    val payload = claims.joinToString(",", "{", "}") { (k, v) -> if (v is Number) "\"$k\":$v" else "\"$k\":\"$v\"" }
    return enc.encodeToString("""{"alg":"none"}""".toByteArray()) + "." + enc.encodeToString(payload.toByteArray()) + ".sig"
}

class NousAuthTest {
    @org.junit.Test(expected = com.ersaz.hermesmobile.auth.NousAuthException::class)
    fun hermesRejectsApiKeyOnlyWithoutAccountLogin() {
        NousPortal.authenticatedEndpoint(false, NousPortal.INFERENCE)
    }

    @org.junit.Test
    fun loggedInHermesOnlyUsesNousEndpoints() {
        org.junit.Assert.assertEquals(NousPortal.WELCOME, NousPortal.authenticatedEndpoint(true, NousPortal.WELCOME))
        org.junit.Assert.assertEquals(NousPortal.INFERENCE, NousPortal.authenticatedEndpoint(true, "https://other.example/v1"))
    }

    private fun server(vararg responses: MockResponse) = MockWebServer().apply { responses.forEach { enqueue(it) }; start() }
    private fun json(code: Int, body: String) = MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    @Test
    fun deviceFlowPollsUntilApprovedAndStoresSession() = runBlocking {
        val access = jwt("exp" to 4_102_444_800L, "email" to "me@example.com")
        val srv = server(
            json(200, """{"device_code":"dc_1","user_code":"ABCD-EFGH","verification_uri":"https://p/x","verification_uri_complete":"https://p/x?user_code=ABCD-EFGH","expires_in":600,"interval":1}"""),
            json(400, """{"error":"authorization_pending"}"""),
            json(200, """{"access_token":"$access","refresh_token":"rt_1","expires_in":900,"scope":"inference:invoke","token_type":"Bearer"}"""),
        )
        val store = FakeStore()
        val auth = NousAuth(OkHttpClient(), store, portal = srv.url("/").toString().trimEnd('/'))
        val dc = auth.requestDeviceCode()
        assertEquals("ABCD-EFGH", dc.userCode)
        assertEquals("https://p/x?user_code=ABCD-EFGH", dc.verificationUriComplete)

        auth.awaitApproval(dc)
        assertTrue(auth.signedIn)
        assertEquals("nous_oauth", store.authMode)
        assertEquals(access, store.nousAccessToken)
        assertEquals("rt_1", store.nousRefreshToken)
        assertEquals("me@example.com", store.nousAccount)
        assertEquals(4_102_444_800_000L, store.nousExpiresAt)

        val first = srv.takeRequest().body.readUtf8()
        assertTrue(first, "client_id=hermes-cli" in first && "scope=inference%3Ainvoke" in first)
        srv.takeRequest()
        val poll = srv.takeRequest()
        assertEquals("/api/oauth/token", poll.path)
        val body = poll.body.readUtf8()
        assertTrue(body, "device_code=dc_1" in body && "grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Adevice_code" in body)
        srv.shutdown()
    }

    @Test
    fun deniedAndExpiredCodesSurfaceAsErrors() = runBlocking {
        val srv = server(
            json(200, """{"device_code":"d","user_code":"U","verification_uri":"https://p","verification_uri_complete":"https://p?c=U","expires_in":600,"interval":1}"""),
            json(400, """{"error":"access_denied"}"""),
        )
        val auth = NousAuth(OkHttpClient(), FakeStore(), portal = srv.url("/").toString().trimEnd('/'))
        val dc = auth.requestDeviceCode()
        try { auth.awaitApproval(dc); fail("should have thrown") } catch (e: NousAuthException) { assertEquals("access_denied", e.code) }
        srv.shutdown()
    }

    @Test
    fun refreshesExpiringTokenWithHeaderAndKeepsFreshOnes() = runBlocking {
        val fresh = jwt("exp" to 4_102_444_800L)
        val srv = server(json(200, """{"access_token":"$fresh","expires_in":900}"""))
        var clock = 1_000_000L
        val store = FakeStore().apply {
            authMode = "nous_oauth"; nousAccessToken = "old"; nousRefreshToken = "rt_old"; nousExpiresAt = clock + 30_000 // < 2 min skew
        }
        val auth = NousAuth(OkHttpClient(), store, portal = srv.url("/").toString().trimEnd('/'), now = { clock })
        assertEquals(fresh, auth.accessToken())
        assertEquals("rt_old", store.nousRefreshToken) // refresh response had none: the old one is kept
        val req = srv.takeRequest()
        assertEquals("rt_old", req.getHeader("x-nous-refresh-token"))
        assertTrue(req.body.readUtf8().contains("grant_type=refresh_token"))
        // Now valid for years: no second network call.
        assertEquals(fresh, auth.accessToken())
        assertEquals(1, srv.requestCount)
        srv.shutdown()
    }

    @Test
    fun rejectedRefreshAsksForRelogin() = runBlocking {
        val srv = server(json(400, """{"error":"invalid_grant"}"""))
        val store = FakeStore().apply { authMode = "nous_oauth"; nousAccessToken = "x"; nousRefreshToken = "r"; nousExpiresAt = 0 }
        val auth = NousAuth(OkHttpClient(), store, portal = srv.url("/").toString().trimEnd('/'))
        try { auth.accessToken(); fail() } catch (e: NousAuthException) { assertTrue(e.reloginRequired) }
        auth.logout()
        assertFalse(auth.signedIn)
        assertNull(auth.accessToken())
        srv.shutdown()
    }

    @Test
    fun inferenceUrlsAreRestrictedToNousHosts() {
        assertEquals("https://inference-api.nousresearch.com/v1", NousPortal.safeInferenceUrl("https://inference-api.nousresearch.com/v1/"))
        assertEquals(NousPortal.WELCOME, NousPortal.safeInferenceUrl(NousPortal.WELCOME))
        assertNull(NousPortal.safeInferenceUrl("https://evil.example/v1"))
        assertNull(NousPortal.safeInferenceUrl("http://inference-api.nousresearch.com/v1"))
        assertNull(NousPortal.safeInferenceUrl("https://evil.inference-api.nousresearch.com.attacker.io/v1"))
    }

    @Test
    fun llmClientRefreshesKeyOnceOn401() = runBlocking {
        val srv = server(
            json(401, """{"error":{"message":"jwt expired"}}"""),
            json(200, """{"choices":[{"message":{"content":"OK"},"finish_reason":"stop"}]}"""),
        )
        val calls = mutableListOf<Boolean>()
        val client = LlmClient(OkHttpClient(), srv.url("/v1").toString(), "", "nous") { force -> calls += force; if (force) "new-token" else "old-token" }
        val r = client.chat("m", listOf(Message("user", "hi")), stream = false)
        assertEquals("OK", r.content)
        assertEquals(listOf(false, true), calls)
        assertEquals("Bearer old-token", srv.takeRequest().getHeader("Authorization"))
        assertEquals("Bearer new-token", srv.takeRequest().getHeader("Authorization"))
        srv.shutdown()
    }

    @Test
    fun persistent401SurfacesAsEntitlementError() = runBlocking {
        val srv = server(json(403, """{"error":{"message":"subscription required"}}"""))
        val client = LlmClient(OkHttpClient(), srv.url("/v1").toString(), "k", "nous")
        try { client.chat("m", listOf(Message("user", "hi")), stream = false); fail() }
        catch (e: LlmException) { assertEquals(403, e.status); assertTrue(e.isEntitlementError()) }
        srv.shutdown()
    }
}

class ModelCatalogTest {
    private val sample = """
    {"data":[
      {"id":"anthropic/claude-sonnet-5.5","name":"Claude Sonnet 5.5","context_length":1000000,
       "architecture":{"output_modalities":["text"]},
       "pricing":{"prompt":"0.000003","completion":"0.000015"},"supported_parameters":["tools","reasoning","temperature"]},
      {"id":"poolside/laguna-s-2.1:free","name":"Laguna S","context_length":256000,
       "architecture":{"output_modalities":["text"]},"pricing":{"prompt":"0","completion":"0"},"supported_parameters":["tools"]},
      {"id":"stealth/zero","name":"Zero","context_length":64000,"pricing":{"prompt":"0.0000000000","completion":"0.0000000000"},"supported_parameters":["temperature"]},
      {"id":"google/gemini-nano-banana","name":"Image","architecture":{"output_modalities":["image"]},"pricing":{"prompt":"1","completion":"1"}},
      {"id":"openai/gpt-x:batch","name":"batch","pricing":{"prompt":"1","completion":"1"}},
      {"id":"plain-model"}
    ]}"""

    @Test
    fun parsesPricingContextAndCapabilities() {
        val m = ModelCatalog.parse(sample).associateBy { it.id }
        assertEquals(setOf("anthropic/claude-sonnet-5.5", "poolside/laguna-s-2.1:free", "stealth/zero", "plain-model"), m.keys)
        assertFalse(m.getValue("anthropic/claude-sonnet-5.5").free)
        assertTrue(m.getValue("anthropic/claude-sonnet-5.5").supportsReasoning)
        assertEquals("$3.00 / $15.00 per 1M", m.getValue("anthropic/claude-sonnet-5.5").priceLabel())
        assertTrue(m.getValue("poolside/laguna-s-2.1:free").free)
        assertTrue("zero-priced counts as free", m.getValue("stealth/zero").free)
        assertFalse("no tools advertised", m.getValue("stealth/zero").supportsTools)
        assertFalse("unknown pricing is not free", m.getValue("plain-model").free)
        assertTrue("no capability info: assume tools", m.getValue("plain-model").supportsTools)
    }

    @Test
    fun picksDefaultsForPaidAndFreeAccounts() {
        val list = ModelCatalog.parse(sample)
        assertEquals("anthropic/claude-sonnet-5.5", ModelCatalog.pickPaidDefault(list)?.id)
        assertEquals(listOf("poolside/laguna-s-2.1:free"), ModelCatalog.freeAgentModels(list).map { it.id })
    }
}
