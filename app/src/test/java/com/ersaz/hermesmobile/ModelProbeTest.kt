package com.ersaz.hermesmobile
import com.ersaz.hermesmobile.core.ModelProfile
import com.ersaz.hermesmobile.llm.*
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
class ModelProbeTest {
 @Test fun testUsesOnlySelectedProfileAndMinimalSyntheticMessage() = runBlocking {
  val server=MockWebServer(); server.start()
  try {
   server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"OK"},"finish_reason":"stop"}]}"""))
   val p=ModelProfile("test","custom",server.url("/v1").toString(),"secret-key","target")
   val result=ModelProbe({ LlmClient(OkHttpClient(),it.baseUrl,it.apiKey,it.providerId) },System::currentTimeMillis).test(p)
   assertEquals(ProbeStatus.RESPONDED,result.status); assertEquals(p.id,result.profileId)
   val body=JSONObject(server.takeRequest().body.readUtf8())
   assertEquals("target",body.getString("model")); assertEquals(1,body.getJSONArray("messages").length())
   assertEquals(16,body.getInt("max_tokens")); assertFalse(result.detail.contains("secret-key"))
  } finally { server.shutdown() }
 }
 @Test fun providerRestrictionIsNotRetriedOrExposedAsReady()=runBlocking {
  val server=MockWebServer();server.start()
  try {
   server.enqueue(MockResponse().setResponseCode(403).setBody("""{"error":{"type":"FreeTierError","message":"secret-key only within OpenCode"}}"""))
   val p=ModelProfile("test","custom",server.url("/v1").toString(),"secret-key","m")
   val result=ModelProbe({ LlmClient(OkHttpClient(),it.baseUrl,it.apiKey,it.providerId) },System::currentTimeMillis).test(p)
   assertEquals(ProbeStatus.RESTRICTED,result.status);assertEquals(1,server.requestCount)
   assertFalse(result.detail.contains("secret-key"))
  } finally {server.shutdown()}
 }
}
