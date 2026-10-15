package com.ersaz.hermesmobile

import com.ersaz.hermesmobile.router.OpenCodeAccess
import com.ersaz.hermesmobile.router.OpenCodeAccessPolicy
import com.ersaz.hermesmobile.router.OpenCodeCapability
import org.junit.Assert.*
import org.junit.Test

class OpenCodeAccessPolicyTest {
    @Test fun freeTier403IsNotSelectable() {
        val result = OpenCodeAccessPolicy.afterProbe("oc/example", 403, "FreeTierError")
        assertEquals(OpenCodeAccess.INFERENCE_RESTRICTED, result.access)
        assertFalse(result.selectable)
    }

    @Test fun successfulProbeCanBeSelected() {
        val result = OpenCodeAccessPolicy.afterProbe("oc/example", 200, null)
        assertTrue(result.selectable)
    }

    @Test fun catalogAndLoginAreNotProofOfAccess() {
        assertFalse(OpenCodeCapability(OpenCodeAccess.CATALOG_ONLY).selectable)
        assertFalse(OpenCodeCapability(OpenCodeAccess.CLI_AUTHENTICATED).selectable)
        assertFalse(OpenCodeCapability(OpenCodeAccess.CLI_LOGIN_REQUIRED).selectable)
    }

    @Test fun transientErrorsDoNotClaimAccess() {
        assertFalse(OpenCodeAccessPolicy.afterProbe("oc/example", 500, "server failed").selectable)
        assertFalse(OpenCodeAccessPolicy.afterProbe("oc/example", null, "offline").selectable)
    }
}
