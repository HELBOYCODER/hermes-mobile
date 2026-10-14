package com.ersaz.hermesmobile

import com.ersaz.hermesmobile.auth.NousPortal
import com.ersaz.hermesmobile.auth.NousSetup
import com.ersaz.hermesmobile.core.ModelProfile
import com.ersaz.hermesmobile.core.nousCatalogProfiles
import com.ersaz.hermesmobile.llm.ModelInfo
import org.junit.Assert.*
import org.junit.Test

class NousCatalogTest {
    @Test fun refreshingNousCatalogDoesNotDuplicatePreviouslySavedCurrentModel() {
        val saved = ModelProfile("Current", "nous", NousPortal.INFERENCE, "", "model-1")
        val unrelated = ModelProfile("Other", "custom", "http://local/v1", "", "other")
        val catalog = nousCatalogProfiles(listOf(ModelInfo("model-1", "Model 1", 128000, 0.0, 0.0, true, false)))
        val result = com.ersaz.hermesmobile.core.replaceModelSource(listOf(saved, unrelated), "nous-catalog", catalog)
        assertEquals(2, result.size)
        assertEquals(1, result.count { it.id == saved.id })
        assertTrue(result.single { it.id == saved.id }.free)
        assertTrue(result.contains(unrelated))
    }

    @Test fun completeCatalogRetainsFreeAndPremiumModelsWithoutTruncation() {
        val models = (0..600).map { i ->
            ModelInfo("model-$i", "Model $i", 128000, if (i % 2 == 0) 0.0 else 0.000001,
                if (i % 2 == 0) 0.0 else 0.000002, true, false)
        }
        val profiles = nousCatalogProfiles(models)
        assertEquals(601, profiles.size)
        assertEquals(301, profiles.count { it.free })
        assertEquals(300, profiles.count { !it.free })
        assertTrue(profiles.all { it.providerId == "nous" && it.apiKey.isEmpty() && it.baseUrl == NousPortal.INFERENCE })
        assertTrue(profiles[1].priceLabel.contains("1M"))
        assertEquals(profiles[1], ModelProfile.fromJson(profiles[1].toJson()))
    }
    @Test fun freeWelcomeModelKeepsItsOwnEndpointAlongsidePremiumCatalog() {
        val p = nousCatalogProfiles(listOf(ModelInfo(NousSetup.WELCOME_MODEL, "Welcome", 128000, 0.0, 0.0, true, false))).single()
        assertTrue(p.free)
        assertEquals(NousPortal.WELCOME, p.baseUrl)
    }
}
