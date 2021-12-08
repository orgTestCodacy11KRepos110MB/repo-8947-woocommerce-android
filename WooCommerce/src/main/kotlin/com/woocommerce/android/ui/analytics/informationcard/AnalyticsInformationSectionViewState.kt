package com.woocommerce.android.ui.analytics.informationcard

data class AnalyticsInformationSectionViewState(
    val title: String,
    val value: String,
    val delta: Int
) {
    val sign: String
        get() = when {
            delta > 0 -> "+"
            delta == 0 -> ""
            else -> "-"
        }
}
