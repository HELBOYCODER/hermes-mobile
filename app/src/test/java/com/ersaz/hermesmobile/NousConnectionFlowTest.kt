package com.ersaz.hermesmobile

import com.ersaz.hermesmobile.auth.*
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class NousConnectionFlowTest {
    @Test fun configureFailureRetainsAuthorization() = runBlocking {
        var authorized = false
        val result = NousConnectionFlow(authorize = { authorized = true }, configure = { throw IOException("offline") }).run()
        assertTrue(authorized)
        assertTrue(result is NousConnectionResult.AuthorizedWithoutModel)
    }
    @Test fun authorizationFailureDoesNotRunConfigure() = runBlocking {
        var configured = false
        try {
            NousConnectionFlow({ throw IOException("denied") }, { configured = true; error("unexpected") }).run()
            fail("must fail")
        } catch (_: IOException) { }
        assertFalse(configured)
    }
    @Test fun cancellationNeverBecomesSuccessfulLogin() = runBlocking {
        try {
            NousConnectionFlow({}, { throw CancellationException("cancel") }).run()
            fail("must cancel")
        } catch (_: CancellationException) { }
    }
}
