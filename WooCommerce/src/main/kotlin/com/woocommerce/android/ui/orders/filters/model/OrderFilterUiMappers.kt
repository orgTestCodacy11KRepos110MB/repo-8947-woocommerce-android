package com.woocommerce.android.ui.orders.filters.model

import com.woocommerce.android.R
import com.woocommerce.android.ui.orders.filters.data.DateRange
import com.woocommerce.android.ui.orders.filters.data.DateRange.LAST_2_DAYS
import com.woocommerce.android.ui.orders.filters.data.DateRange.THIS_MONTH
import com.woocommerce.android.ui.orders.filters.data.DateRange.THIS_WEEK
import com.woocommerce.android.ui.orders.filters.data.DateRange.TODAY
import com.woocommerce.android.ui.orders.filters.data.DateRangeFilterOption
import com.woocommerce.android.ui.orders.filters.data.OrderStatusOption
import com.woocommerce.android.viewmodel.ResourceProvider

fun OrderStatusOption.toOrderFilterOptionUiModel(resourceProvider: ResourceProvider) =
    OrderFilterOptionUiModel(
        key = key,
        displayName = getDisplayNameForOrderStatusFilter(resourceProvider),
        isSelected = isSelected
    )

fun OrderStatusOption.getDisplayNameForOrderStatusFilter(resourceProvider: ResourceProvider) =
    if (statusCount > 0) {
        resourceProvider.getString(R.string.orderfilters_order_status_with_count_filter_option, label, statusCount)
    } else {
        label
    }

fun DateRangeFilterOption.toOrderFilterOptionUiModel(resourceProvider: ResourceProvider) =
    OrderFilterOptionUiModel(
        key = dateRange.filterKey,
        displayName = dateRange.toDisplayName(resourceProvider),
        isSelected = isSelected
    )

fun DateRange.toDisplayName(resourceProvider: ResourceProvider): String =
    resourceProvider.getString(
        when (this) {
            TODAY -> R.string.orderfilters_date_range_filter_today
            LAST_2_DAYS -> R.string.orderfilters_date_range_filter_last_two_days
            THIS_WEEK -> R.string.orderfilters_date_range_filter_this_week
            THIS_MONTH -> R.string.orderfilters_date_range_filter_this_month
        }
    )
