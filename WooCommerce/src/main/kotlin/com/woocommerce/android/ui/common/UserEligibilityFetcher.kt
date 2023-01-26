package com.woocommerce.android.ui.common

import com.woocommerce.android.AppPrefs
import com.woocommerce.android.WooException
import com.woocommerce.android.model.User
import com.woocommerce.android.model.toAppModel
import com.woocommerce.android.tools.SelectedSite
import org.wordpress.android.fluxc.store.WCUserStore
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UserEligibilityFetcher @Inject constructor(
    private val appPrefs: AppPrefs,
    private val userStore: WCUserStore,
    private val selectedSite: SelectedSite
) {
    suspend fun fetchUserInfo(): Result<User> {
        return userStore.fetchUserRole(selectedSite.get()).let {
            when {
                it.isError -> Result.failure(WooException(it.error))
                it.model != null -> Result.success(it.model!!.toAppModel())
                else -> Result.failure(NullPointerException("Response is null"))
            }
        }.onSuccess {
            updateUserInfo(it)
        }
    }

    fun getUserByEmail(email: String) = userStore.getUserByEmail(selectedSite.get(), email)?.toAppModel()

    private fun updateUserInfo(user: User) {
        appPrefs.setIsUserEligible(user.isEligible)
        appPrefs.setUserEmail(user.email)
    }
}
