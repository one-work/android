package com.xcprinter.app.bridge

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
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
  
  // manager 回调设置 + 首次 ready 回调只做一次（对照 iOS CBCentralManager
  // 初始化时 didUpdateState 只触发一次）。否则每条 JS 消息都会重复触发
  // refreshReadyState -> replyTo("connect", ready=true)，与 JS 形成死循环。
  private var didSetupManager = false

  override fun onReceive(message: Message) {
    if (!didSetupManager) {
      didSetupManager = true
      setupBluetoothManager()
    }

    when (message.event) {
      "connect" -> handleConnect()
      "search" -> ensurePermissionsThen { BluetoothPrinterManager.startScan() }
      "connect_device" -> ensurePermissionsThen { handleConnectDevice(message) }
      "disconnect_device" -> handleDisconnectDevice(message)
      "send_data" -> ensurePermissionsThen { handleSendData(message) }
      else -> Log.w(TAG, "Unknown event for message: $message")
    }
  }
  
  // 蓝牙相关操作前的权限保障：已授权直接执行，否则先弹运行时权限申请，
  // 授权结果返回后继续执行（无论是否授权都执行，Manager 内部会兜底失败回调）。
  private var pendingAction: (() -> Unit)? = null
  private var permissionLauncher: ActivityResultLauncher<Array<String>>? = null

  private fun ensurePermissionsThen(action: () -> Unit) {
    if (BluetoothPrinterManager.hasBluetoothPermissions()) {
      action()
      return
    }

    val activity = fragment.activity ?: run {
      Log.w(TAG, "无法申请蓝牙权限：Fragment 未附加到 Activity")
      return
    }

    pendingAction = action
    permissionLauncher?.unregister()
    // 使用 Activity 的 ActivityResultRegistry，可在任意时刻注册（不受 Fragment 生命周期限制）
    val launcher = activity.activityResultRegistry.register(
      PERMISSION_REQUEST_KEY,
      ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
      permissionLauncher?.unregister()
      permissionLauncher = null
      val granted = result.isNotEmpty() && result.values.all { it }
      if (!granted) Log.w(TAG, "蓝牙权限被拒绝")
      val action2 = pendingAction
      pendingAction = null
      action2?.invoke()
    }
    permissionLauncher = launcher
    launcher.launch(BluetoothPrinterManager.requiredPermissions())
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
    const val PERMISSION_REQUEST_KEY = "bluetooth_permissions"
  }
}
