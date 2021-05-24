package com.woocommerce.android.ui.orders.fulfill

import android.os.Parcelable
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.SavedStateHandle
import com.woocommerce.android.AppPrefs
import com.woocommerce.android.R
import com.woocommerce.android.annotations.OpenClassOnDebug
import com.woocommerce.android.model.Order
import com.woocommerce.android.model.Order.Item
import com.woocommerce.android.model.OrderShipmentTracking
import com.woocommerce.android.tools.NetworkStatus
import com.woocommerce.android.viewmodel.LiveDataDelegate
import com.woocommerce.android.viewmodel.ResourceProvider
import com.woocommerce.android.viewmodel.ScopedViewModel
import com.woocommerce.android.viewmodel.navArgs
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.parcelize.Parcelize
import org.wordpress.android.fluxc.model.order.OrderIdSet
import org.wordpress.android.fluxc.model.order.toIdSet
import javax.inject.Inject

@OpenClassOnDebug
@HiltViewModel
class OrderFulfillViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val appPrefs: AppPrefs,
    private val networkStatus: NetworkStatus,
    private val resourceProvider: ResourceProvider,
    private val repository: OrderFulfillRepository
) : ScopedViewModel(savedState) {
    private val navArgs: OrderFulfillFragmentArgs by savedState.navArgs()

    final val viewStateData = LiveDataDelegate(savedState, ViewState())
    private var viewState by viewStateData

    private val orderIdSet: OrderIdSet
        get() = navArgs.orderIdentifier.toIdSet()

    private val _productList = MutableLiveData<List<Item>>()
    val productList: LiveData<List<Item>> = _productList

    private val _shipmentTrackings = MutableLiveData<List<OrderShipmentTracking>>()
    val shipmentTrackings: LiveData<List<OrderShipmentTracking>> = _shipmentTrackings

    final var order: Order
        get() = requireNotNull(viewState.order)
        set(value) {
            viewState = viewState.copy(
                order = value
            )
        }

    init {
        start()
    }

    final fun start() {
        val order = repository.getOrder(navArgs.orderIdentifier)
        order?.let {
            displayOrderDetails(it)
            displayOrderProducts(it)
            displayShipmentTrackings()
        }
    }

    private fun displayOrderDetails(order: Order) {
        viewState = viewState.copy(
            order = order,
            toolbarTitle = resourceProvider.getString(R.string.order_fulfill_title)
        )
    }

    private fun displayOrderProducts(order: Order) {
        val products = repository.getNonRefundedProducts(orderIdSet.remoteOrderId, order.items)
        _productList.value = products
    }

    private fun displayShipmentTrackings() {
        val trackingAvailable = appPrefs.isTrackingExtensionAvailable() && !hasVirtualProductsOnly()
        viewState = viewState.copy(isShipmentTrackingAvailable = trackingAvailable)
        if (trackingAvailable) {
            _shipmentTrackings.value = repository.getOrderShipmentTrackings(orderIdSet.id)
        }
    }

    fun hasVirtualProductsOnly(): Boolean {
        return if (order.items.isNotEmpty()) {
            val remoteProductIds = order.getProductIds()
            repository.hasVirtualProductsOnly(remoteProductIds)
        } else false
    }

    @Parcelize
    data class ViewState(
        val order: Order? = null,
        val toolbarTitle: String? = null,
        val isShipmentTrackingAvailable: Boolean? = null
    ) : Parcelable
}
