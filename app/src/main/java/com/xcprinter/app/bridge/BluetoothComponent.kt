package com.xcprinter.app.bridge

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.fragment.app.Fragment
import dev.hotwire.core.bridge.BridgeComponent
import dev.hotwire.core.bridge.BridgeDelegate
import dev.hotwire.core.bridge.Message
import dev.hotwire.navigation.destinations.HotwireDestination
import com.xcprinter.app.bt.BluetoothPrinterManager
import com.xcprinter.app.bt.DeviceInfo
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 蓝牙打印组件（对照 iOS Bridge/BluetoothComponent.swift）。
 * 事件与 JSON 契约与 iOS 完全一致：
 * connect / search / connect_device / disconnect_device / send_data
 */
class BluetoothComponent(
    name: String,
    private val delegate: BridgeDelegate<HotwireDestination>
) : BridgeComponent<HotwireDestination>(name, delegate) {

    private val fragment: Fragment
        get() = delegate.destination.fragment

    private val prefs: SharedPreferences
        get() = fragment.requireContext()
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun onReceive(message: Message) {
        setupBluetoothManager()

        when (message.event) {
            "connect" -> handleConnect()
            "search" -> BluetoothPrinterManager.startScan()
            "connect_device" -> handleConnectDevice(message)
            "disconnect_device" -> handleDisconnectDevice(message)
            "send_data" -> handleSendData(message)
            else -> Log.w(TAG, "Unknown event for message: $message")
        }
    }

    // 对照 iOS setupBluetoothManager：先设置好各回调
    private fun setupBluetoothManager() {
        BluetoothPrinterManager.onReady = { ready ->
            replyTo(
                "connect",
                ConnectStatus(
                    address = prefs.getString("address", null),
                    name = prefs.getString("name", null),
                    ready = ready
                )
            )
        }
        BluetoothPrinterManager.onDeviceFound = { device ->
            replyTo("search", SearchResult(device = device))
        }
        BluetoothPrinterManager.onConnected = { success ->
            replyTo("connect_device", ConnectResult(success = success))
        }
        BluetoothPrinterManager.onDisconnected = { success ->
            replyTo("disconnect_device", ConnectResult(success = success))
        }
        BluetoothPrinterManager.onDataSent = { result ->
            replyTo("send_data", SendResult(success = result.success, error = result.error))
        }

        // 蓝牙已开启时立即回调 onReady（对照 iOS CBCentralManager 初始化行为）
        BluetoothPrinterManager.refreshReadyState()
    }

    private fun handleConnect() {
        val address = prefs.getString("address", null)
        val name = prefs.getString("name", null)
        Log.d(TAG, "connect address: $address")

        // 有保存的 address 时填实际状态字符串，否则默认 disconnected
        val state = if (address != null) BluetoothPrinterManager.connectionState else "disconnected"

        replyTo("connect", ConnectStatus(address = address, name = name, state = state))
    }

    private fun handleConnectDevice(message: Message) {
        val data = message.data<ConnectDeviceData>() ?: return
        prefs.edit()
            .putString("address", data.address)
            .putString("name", data.name)
            .apply()
        BluetoothPrinterManager.connect(data.address)
    }

    private fun handleDisconnectDevice(message: Message) {
        val data = message.data<DisconnectDeviceData>() ?: return
        prefs.edit()
            .remove("address")
            .remove("name")
            .apply()
        BluetoothPrinterManager.disconnect(data.address)
    }

    private fun handleSendData(message: Message) {
        val data = message.data<SendData>() ?: return
        BluetoothPrinterManager.send(data.data.map { it.toByte() }.toByteArray())
    }

    // MARK: - 接收数据模型
    @Serializable
    private data class ConnectDeviceData(
        @SerialName("address") val address: String,
        @SerialName("name") val name: String
    )

    @Serializable
    private data class DisconnectDeviceData(
        @SerialName("address") val address: String
    )

    @Serializable
    private data class SendData(
        @SerialName("data") val data: List<Int>
    )

    // MARK: - 发送数据模型
    @Serializable
    private data class SearchResult(
        @SerialName("device") val device: DeviceInfo
    )

    @Serializable
    private data class ConnectResult(
        @SerialName("success") val success: Boolean
    )

    @Serializable
    private data class SendResult(
        @SerialName("success") val success: Boolean,
        @SerialName("error") val error: String?
    )

    @Serializable
    private data class ConnectStatus(
        @SerialName("address") val address: String?,
        @SerialName("name") val name: String?,
        @SerialName("state") val state: String = "",
        @SerialName("ready") val ready: Boolean = false
    )

    private companion object {
        const val TAG = "BluetoothComponent"
        const val PREFS_NAME = "bluetooth"
    }
}
