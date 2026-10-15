package com.ersaz.hermesmobile.auth

import kotlinx.coroutines.CancellationException

sealed class NousConnectionResult {
    data class Ready(val route: NousRoute) : NousConnectionResult()
    data class AuthorizedWithoutModel(val message: String) : NousConnectionResult()
}

/** Authorization succeeds independently of catalogue, billing and inference availability. */
class NousConnectionFlow(
    private val authorize: suspend () -> Unit,
    private val configure: suspend () -> NousRoute,
) {
    suspend fun run(): NousConnectionResult {
        authorize()
        return try { NousConnectionResult.Ready(configure()) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { NousConnectionResult.AuthorizedWithoutModel(
            "Account connected. Model access could not be checked. Retry access or choose a model.") }
    }
}
