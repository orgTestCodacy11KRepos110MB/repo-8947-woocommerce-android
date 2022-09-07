package com.woocommerce.android.ui.appwidgets.stats.today

import com.woocommerce.android.ui.appwidgets.WidgetUpdater
import com.woocommerce.android.ui.appwidgets.stats.StatsWidgetProvider
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class TodayStatsWidget : StatsWidgetProvider() {
    @Inject lateinit var todayWidgetUpdater: TodayWidgetUpdater
    override val widgetUpdater: WidgetUpdater
        get() = todayWidgetUpdater
}
