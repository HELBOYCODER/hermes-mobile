package com.ersaz.hermesmobile.llm

import com.ersaz.hermesmobile.core.ModelProfile

data class ModelGroup(val id: String, val profileIds: List<String>, val allowPaid: Boolean = false)
object ModelRoutingPolicy {
    fun next(group: ModelGroup, profiles: List<ModelProfile>, attempted: Set<String>, cooldowns: Map<String, Long>, nowMs: Long): ModelProfile? {
        val byId = profiles.associateBy { it.id }
        return group.profileIds.asSequence().mapNotNull { byId[it] }.firstOrNull {
            it.id !in attempted && (group.allowPaid || it.free) && (cooldowns[it.id] ?: 0) <= nowMs
        }
    }
}
