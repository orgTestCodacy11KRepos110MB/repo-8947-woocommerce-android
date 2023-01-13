package com.woocommerce.android.cardreader.internal.connection

import com.stripe.stripeterminal.external.callable.Callback
import com.stripe.stripeterminal.external.callable.ReaderCallback
import com.stripe.stripeterminal.external.models.ConnectionConfiguration
import com.stripe.stripeterminal.external.models.DeviceType
import com.stripe.stripeterminal.external.models.Reader
import com.stripe.stripeterminal.external.models.TerminalException
import com.woocommerce.android.cardreader.connection.CardReader
import com.woocommerce.android.cardreader.connection.CardReaderDiscoveryEvents
import com.woocommerce.android.cardreader.connection.CardReaderImpl
import com.woocommerce.android.cardreader.connection.CardReaderStatus
import com.woocommerce.android.cardreader.connection.CardReaderTypesToDiscover
import com.woocommerce.android.cardreader.connection.CardReaderTypesToDiscover.SpecificReaders
import com.woocommerce.android.cardreader.connection.CardReaderTypesToDiscover.SpecificReaders.SpecificBuiltInReaders
import com.woocommerce.android.cardreader.connection.CardReaderTypesToDiscover.SpecificReaders.SpecificExternalReaders
import com.woocommerce.android.cardreader.connection.CardReaderTypesToDiscover.UnspecifiedReaders
import com.woocommerce.android.cardreader.internal.connection.actions.DiscoverReadersAction
import com.woocommerce.android.cardreader.internal.connection.actions.DiscoverReadersAction.DiscoverReadersStatus
import com.woocommerce.android.cardreader.internal.wrappers.TerminalWrapper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

internal class ConnectionManager(
    private val terminal: TerminalWrapper,
    private val bluetoothReaderListener: BluetoothReaderListenerImpl,
    private val discoverReadersAction: DiscoverReadersAction,
    private val terminalListenerImpl: TerminalListenerImpl,
) {
    val softwareUpdateStatus = bluetoothReaderListener.updateStatusEvents
    val softwareUpdateAvailability = bluetoothReaderListener.updateAvailabilityEvents
    val batteryStatus = bluetoothReaderListener.batteryStatusEvents
    val displayBluetoothCardReaderMessages = bluetoothReaderListener.displayMessagesEvents

    fun discoverReaders(isSimulated: Boolean, cardReaderTypesToDiscover: CardReaderTypesToDiscover) =
        when (cardReaderTypesToDiscover) {
            is SpecificReaders -> {
                when (cardReaderTypesToDiscover) {
                    is SpecificBuiltInReaders -> discoverReadersAction.discoverBuildInReaders(isSimulated)
                    is SpecificExternalReaders -> discoverReadersAction.discoverBtReaders(isSimulated)
                }
            }
            UnspecifiedReaders -> merge(
                discoverReadersAction.discoverBuildInReaders(isSimulated),
                discoverReadersAction.discoverBtReaders(isSimulated)
            )
        }.map { state ->
            when (state) {
                is DiscoverReadersStatus.Started -> {
                    CardReaderDiscoveryEvents.Started
                }
                is DiscoverReadersStatus.Failure -> {
                    CardReaderDiscoveryEvents.Failed(state.exception.errorMessage)
                }
                is DiscoverReadersStatus.FoundReaders -> {
                    val filtering: (Reader) -> Boolean = when (cardReaderTypesToDiscover) {
                        is SpecificReaders -> { reader ->
                            cardReaderTypesToDiscover.readers.map { it.name }.contains(reader.deviceType.name)
                        }
                        UnspecifiedReaders -> { _ -> true }
                    }
                    CardReaderDiscoveryEvents.ReadersFound(state.readers.filter(filtering).map { CardReaderImpl(it) })
                }
                DiscoverReadersStatus.Success -> {
                    CardReaderDiscoveryEvents.Succeeded
                }
            }
        }

    fun startConnectionToReader(cardReader: CardReader, locationId: String) {
        (cardReader as CardReaderImpl).let {
            updateReaderStatus(CardReaderStatus.Connecting)
            val readerCallback = object : ReaderCallback {
                override fun onSuccess(reader: Reader) {
                    updateReaderStatus(CardReaderStatus.Connected(CardReaderImpl(reader)))
                }

                override fun onFailure(e: TerminalException) {
                    updateReaderStatus(CardReaderStatus.NotConnected(e.errorMessage))
                }
            }

            if (it.cardReader.deviceType == DeviceType.COTS_DEVICE) {
                terminal.connectToMobile(
                    cardReader.cardReader,
                    ConnectionConfiguration.LocalMobileConnectionConfiguration(locationId),
                    readerCallback
                )
            } else {
                terminal.connectToReader(
                    cardReader.cardReader,
                    ConnectionConfiguration.BluetoothConnectionConfiguration(locationId),
                    readerCallback,
                    bluetoothReaderListener,
                )
            }
        }
    }

    suspend fun disconnectReader() = suspendCoroutine { continuation ->
        terminal.disconnectReader(object : Callback {
            override fun onFailure(e: TerminalException) {
                updateReaderStatus(CardReaderStatus.NotConnected())
                continuation.resume(false)
            }

            override fun onSuccess() {
                updateReaderStatus(CardReaderStatus.NotConnected())
                continuation.resume(true)
            }
        })
    }

    private fun startStateResettingJobIfNeeded(currentStatus: CardReaderStatus) {
        if (currentStatus !is CardReaderStatus.Connecting) return

        val connectedScope = CoroutineScope(Dispatchers.Default)
        connectedScope.launch {
            terminalListenerImpl.readerStatus.collect { connectionStatus ->
                if (connectionStatus is CardReaderStatus.NotConnected) {
                    bluetoothReaderListener.resetConnectionState()
                    connectedScope.cancel()
                }
            }
        }
    }

    fun resetBluetoothCardReaderDisplayMessage() {
        bluetoothReaderListener.resetDisplayMessage()
    }

    private fun updateReaderStatus(status: CardReaderStatus) {
        terminalListenerImpl.updateReaderStatus(status)
        startStateResettingJobIfNeeded(status)
    }
}
