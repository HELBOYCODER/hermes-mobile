package com.ersaz.hermesmobile
import com.ersaz.hermesmobile.core.ModelProfile
import com.ersaz.hermesmobile.llm.*
import org.junit.Assert.*
import org.junit.Test
class ModelRoutingPolicyTest {
 @Test fun excludesPaidCooldownAndAlreadyAttemptedModels() {
  val paid=ModelProfile("p","custom","https://a/v1","","paid")
  val free=paid.copy(model="free",free=true)
  val other=paid.copy(model="other",free=true)
  val g=ModelGroup("g",listOf(paid.id,free.id,other.id))
  assertEquals(other,ModelRoutingPolicy.next(g,listOf(paid,free,other),setOf(free.id),emptyMap(),10))
  assertNull(ModelRoutingPolicy.next(g,listOf(paid,free,other),setOf(free.id),mapOf(other.id to 11L),10))
 }
}
