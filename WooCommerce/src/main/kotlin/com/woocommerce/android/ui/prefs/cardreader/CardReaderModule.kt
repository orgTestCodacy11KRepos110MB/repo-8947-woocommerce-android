package com.woocommerce.android.ui.prefs.cardreader

import com.woocommerce.android.di.FragmentScope
import com.woocommerce.android.ui.prefs.cardreader.CardReaderModule.CardReaderConnectFragmentModule
import com.woocommerce.android.ui.prefs.cardreader.CardReaderModule.CardReaderScanFragmentModule
import com.woocommerce.android.ui.prefs.cardreader.connect.CardReaderConnectFragment
import com.woocommerce.android.ui.prefs.cardreader.connect.CardReaderConnectModule
import com.woocommerce.android.ui.prefs.cardreader.scan.CardReaderScanFragment
import com.woocommerce.android.ui.prefs.cardreader.scan.CardReaderScanModule
import dagger.Module
import dagger.android.ContributesAndroidInjector

@Module(
    includes = [
        CardReaderConnectFragmentModule::class,
        CardReaderScanFragmentModule::class
    ]
)
object CardReaderModule {
    @Module
    abstract class CardReaderConnectFragmentModule {
        @FragmentScope
        @ContributesAndroidInjector(modules = [CardReaderConnectModule::class])
        abstract fun cardReaderConnectFragment(): CardReaderConnectFragment
    }

    @Module
    abstract class CardReaderScanFragmentModule {
        @FragmentScope
        @ContributesAndroidInjector(modules = [CardReaderScanModule::class])
        abstract fun cardReaderScanFragment(): CardReaderScanFragment
    }
}
