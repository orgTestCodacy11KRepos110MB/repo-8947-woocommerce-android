package com.woocommerce.android.ui.login.storecreation.webview

import android.os.Parcelable
import androidx.lifecycle.LiveData
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import com.woocommerce.android.ui.common.wpcomwebview.WPComWebViewAuthenticator
import com.woocommerce.android.ui.login.storecreation.StoreCreationRepository
import com.woocommerce.android.ui.login.storecreation.webview.WebViewStoreCreationViewModel.ViewState.ErrorState
import com.woocommerce.android.ui.login.storecreation.webview.WebViewStoreCreationViewModel.ViewState.StoreCreationState
import com.woocommerce.android.ui.login.storecreation.webview.WebViewStoreCreationViewModel.ViewState.StoreLoadingState
import com.woocommerce.android.util.WooLog
import com.woocommerce.android.util.WooLog.T
import com.woocommerce.android.viewmodel.MultiLiveEvent
import com.woocommerce.android.viewmodel.MultiLiveEvent.Event.Exit
import com.woocommerce.android.viewmodel.ScopedViewModel
import com.woocommerce.android.viewmodel.getStateFlow
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.parcelize.Parcelize
import org.wordpress.android.fluxc.network.UserAgent
import javax.inject.Inject

@HiltViewModel
class WebViewStoreCreationViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    val repository: StoreCreationRepository,
    val wpComWebViewAuthenticator: WPComWebViewAuthenticator,
    val userAgent: UserAgent
) : ScopedViewModel(savedStateHandle) {
    companion object {
        private const val STORE_CREATION_URL = "https://woocommerce.com/start"
        private const val SITE_URL_KEYWORD = "checkout/thank-you/"
        private const val WEBVIEW_EXIT_TRIGGER_KEYWORD = "calypso/images/wpcom-ecommerce"
        private const val STORE_LOAD_RETRIES_LIMIT = 10
    }

    private val step = savedStateHandle.getStateFlow<Step>(viewModelScope, Step.StoreCreation)
    val viewState: LiveData<ViewState> = step.map { step ->
        when (step) {
            Step.StoreCreation -> prepareStoreCreationState()
            Step.StoreLoading -> StoreLoadingState
            Step.StoreLoadingError -> prepareErrorState()
        }
    }.asLiveData()

    private val possibleStoreUrls = mutableListOf<String>()

    private fun prepareStoreCreationState() = StoreCreationState(
        storeCreationUrl = STORE_CREATION_URL,
        siteUrlKeyword = SITE_URL_KEYWORD,
        exitTriggerKeyword = WEBVIEW_EXIT_TRIGGER_KEYWORD,
        onStoreCreated = ::onStoreCreated,
        onSiteAddressFound = ::onSiteAddressFound,
        onBackPressed = {
            triggerEvent(Exit)
        }
    )

    private fun prepareErrorState() = ErrorState(
        onRetryButtonClick = {
            onStoreCreated()
        },
        onBackPressed = {
            triggerEvent(Exit)
        }
    )

    sealed interface ViewState {
        val onBackPressed: () -> Unit
            get() = {}

        data class StoreCreationState(
            val storeCreationUrl: String,
            val siteUrlKeyword: String,
            val exitTriggerKeyword: String,
            val onStoreCreated: () -> Unit,
            val onSiteAddressFound: (url: String) -> Unit,
            override val onBackPressed: () -> Unit
        ) : ViewState

        object StoreLoadingState : ViewState

        data class ErrorState(
            val onRetryButtonClick: () -> Unit,
            override val onBackPressed: () -> Unit
        ) : ViewState
    }

    private fun onStoreCreated() {
        step.value = Step.StoreLoading
        launch {
            // keep fetching the user's sites until the new site is properly configured or the retry limit is reached
            for (retries in 0..STORE_LOAD_RETRIES_LIMIT) {
                val newSite = possibleStoreUrls.firstNotNullOfOrNull { url -> repository.getSiteBySiteUrl(url) }
                if (newSite?.isJetpackInstalled == true && newSite.isJetpackConnected) {
                    WooLog.d(T.LOGIN, "Found new site: ${newSite.url}")
                    repository.selectSite(newSite)
                    triggerEvent(NavigateToNewStore)
                } else if (retries == STORE_LOAD_RETRIES_LIMIT) {
                    WooLog.d(T.LOGIN, "Maximum retries reached...")
                    step.value = Step.StoreLoadingError
                } else {
                    WooLog.d(T.LOGIN, "New site not found, retrying...")
                    val result = repository.fetchSitesAfterCreation()
                    if (result.isFailure) {
                        step.value = Step.StoreLoadingError
                        break
                    }
                }
            }
            WooLog.d(T.LOGIN, "Store creation done...")
        }
    }

    private fun onSiteAddressFound(url: String) {
        possibleStoreUrls.add(url)
    }

    object NavigateToNewStore : MultiLiveEvent.Event()

    private sealed interface Step : Parcelable {
        @Parcelize
        object StoreCreation : Step

        @Parcelize
        object StoreLoading : Step

        @Parcelize
        object StoreLoadingError : Step
    }
}