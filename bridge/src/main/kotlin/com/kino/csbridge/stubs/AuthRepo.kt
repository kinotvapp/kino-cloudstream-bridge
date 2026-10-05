// Headless stand-in for upstream app/src/main/java/com/lagradost/cloudstream3/syncproviders/AuthRepo.kt: same ABI, no UI. Members from cs3-symbols.tsv.
package com.lagradost.cloudstream3.syncproviders

import com.lagradost.cloudstream3.mvvm.safe
import com.lagradost.cloudstream3.syncproviders.AccountManager.Companion.NONE_ID

class PlainAuthRepo(api: AuthAPI) : AuthRepo(api)

/** Same surface as upstream; there are never accounts in the bridge, so every auth lookup is null and login fails. */
abstract class AuthRepo(open val api: AuthAPI) {
    fun isValidRedirectUrl(url: String) = safe { api.isValidRedirectUrl(url) } ?: false
    val idPrefix get() = api.idPrefix
    val name get() = api.name
    val icon get() = api.icon
    val requiresLogin get() = api.requiresLogin
    val createAccountUrl get() = api.createAccountUrl
    val hasOAuth2 get() = api.hasOAuth2
    val hasPin get() = api.hasPin
    val hasInApp get() = api.hasInApp
    val inAppLoginRequirement get() = api.inAppLoginRequirement
    val isAvailable get() = !api.requiresLogin || authUser() != null

    @Throws
    protected suspend fun freshAuth(): AuthData? = null

    @Throws
    fun openOAuth2Page(): Boolean = false
    fun openOAuth2PageWithToast() = Unit
    suspend fun logout(from: AuthUser) = Unit
    fun refreshUser(newAuth: AuthData) = Unit
    fun authData(): AuthData? = null
    fun authToken(): AuthToken? = null
    fun authUser(): AuthUser? = null
    val accounts: Array<AuthData> get() = emptyArray()
    var accountId: Int
        get() = NONE_ID
        set(@Suppress("UNUSED_PARAMETER") value) = Unit

    @Throws
    suspend fun pinRequest(): AuthPinData? = null
    @Throws
    suspend fun login(form: AuthLoginResponse): Boolean = false
    @Throws
    suspend fun login(payload: AuthPinData): Boolean = false
    @Throws
    suspend fun login(redirectUrl: String): Boolean = false
}
