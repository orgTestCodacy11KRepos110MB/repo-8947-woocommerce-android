package com.woocommerce.android.ui.orders.details.editing

import com.woocommerce.android.tools.SelectedSite
import dagger.hilt.android.scopes.ViewModelScoped
import kotlinx.coroutines.flow.Flow
import org.wordpress.android.fluxc.model.LocalOrRemoteId
import org.wordpress.android.fluxc.model.order.OrderAddress
import org.wordpress.android.fluxc.store.OrderUpdateStore
import org.wordpress.android.fluxc.store.WCOrderStore
import javax.inject.Inject

@ViewModelScoped
@Suppress("UnusedPrivateMember")
class OrderEditingRepository @Inject constructor(
    private val orderUpdateStore: OrderUpdateStore,
    private val selectedSite: SelectedSite
) {
    suspend fun updateCustomerOrderNote(
        remoteOrderId: Long,
        customerOrderNote: String
    ): Flow<WCOrderStore.UpdateOrderResult> {
        return orderUpdateStore.updateCustomerOrderNote(
            LocalOrRemoteId.RemoteId(remoteOrderId),
            selectedSite.get(),
            customerOrderNote
        )
    }

    suspend fun updateOrderAddress(
        remoteOrderId: Long,
        orderAddress: OrderAddress
    ): Flow<WCOrderStore.UpdateOrderResult> {
        return orderUpdateStore.updateOrderAddress(
            LocalOrRemoteId.RemoteId(remoteOrderId),
            selectedSite.get().localId(),
            orderAddress
        )
    }

    suspend fun updateBothOrderAddresses(
        remoteOrderId: Long,
        shippingAddress: OrderAddress.Shipping,
        billingAddress: OrderAddress.Billing
    ): Flow<WCOrderStore.UpdateOrderResult> {
        return orderUpdateStore.updateBothOrderAddresses(
            LocalOrRemoteId.RemoteId(remoteOrderId),
            selectedSite.get().localId(),
            shippingAddress,
            billingAddress
        )
    }
}
