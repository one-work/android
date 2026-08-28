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

// 蓝牙打印组件
class BluetoothComponent(
  name: String,
  private val delegate: BridgeDelegate<HotwireDestination>
) : BridgeComponent<HotwireDestination>(name, delegate) {

  private val fragment: Fragment
    get() = delegate.destination.fragment

  private val prefs: SharedPreferences
    get() = fragment.requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

  // 组件只会在 destination 处于 active 状态时因收到消息而被惰性创建
  // （BridgeDelegate.bridgeDidReceiveMessage 会校验 destinationIsActive），
  // 因此创建时即可安全接管全局 BluetoothPrinterManager 的回调。
  init {
    registerManagerCallbacks()
  }

  // 页面回到前台（如返回栈恢复）时组件早已存在、不会再走 init，
  // 需要在这里重新接管回调，确保异步结果 reply 到当前页面的 WebView。
  override fun onStart() {
    super.onStart()
    registerManagerCallbacks()
  }

  // 页面进入后台时释放回调，避免已销毁页面的旧组件"抢答"——
  // 旧组件的 BridgeDelegate 在页面销毁后 bridge 已被置空，
  // 再 reply 只会触发 "bridge is not available" 且 JS 永远收不到结果。
  // 注意：仅当回调仍归本组件所有时才释放。返回栈恢复时新页面的 onStart
  // 可能先于旧页面的 onStop 执行，不能误清新页面刚注册的回调。
  override fun onStop() {
    super.onStop()
    if (callbackOwner === this) {
      callbackOwner = null
      BluetoothPrinterManager.onReady = null
      BluetoothPrinterManager.onDeviceFound = null
      BluetoothPrinterManager.onConnected = null
      BluetoothPrinterManager.onDisconnected = null
      BluetoothPrinterManager.onDataSent = null
    }
  }

  override fun onReceive(message: Message) {
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

  // registerManagerCallbacks：把全局 BluetoothPrinterManager 的各回调
  // 绑定到本组件，并记录归属（供 onStop 判断是否由自己释放）。
  private fun registerManagerCallbacks() {
    callbackOwner = this
    BluetoothPrinterManager.onReady = { ready ->
      val xx = ConnectStatus(address = prefs.getString("address", null), name = prefs.getString("name", null), state = null, ready = ready)
      replyTo("connect", xx)
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

    // 首次 ready 回调整个进程只做一次（对照 iOS CBCentralManager 初始化时
    // didUpdateState 只触发一次），避免重复 replyTo("connect") 与 JS 形成循环
    if (!didRefreshReadyOnce) {
      didRefreshReadyOnce = true
      BluetoothPrinterManager.refreshReadyState()
    }
  }

  private fun handleConnect() {
    val address = prefs.getString("address", null)
    val name = prefs.getString("name", null)
    Log.d(TAG, "connect address: $address")

    if (address != null) {
      val state = BluetoothPrinterManager.connectionState
      val xx = ConnectStatus(address = address, name = name, state = state, ready = null)
      replyTo("connect", xx)
    }
  }

  private fun handleConnectDevice(message: Message) {
    val data = message.data<ConnectDeviceData>() ?: return
    prefs.edit().putString("address", data.address).putString("name", data.name).apply()
    BluetoothPrinterManager.connect(data.address)
  }

  private fun handleDisconnectDevice(message: Message) {
    val data = message.data<DisconnectDeviceData>() ?: return
    prefs.edit().remove("address").remove("name").apply()
    BluetoothPrinterManager.disconnect(data.address)
  }

  private fun handleSendData(message: Message) {
    val data = message.data<SendData>() ?: return
    BluetoothPrinterManager.send(data.data.map { it.toByte() }.toByteArray())
  }

  // 接收数据模型
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

  // 发送数据模型
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
    @SerialName("state") val state: String?,
    @SerialName("ready") val ready: Boolean?
  )

  private companion object {
    const val TAG = "BluetoothComponent"
    const val PREFS_NAME = "bluetooth"
    const val PERMISSION_REQUEST_KEY = "bluetooth_permissions"

    // manager 回调当前归属的组件实例（只在前台页面间移交）
    private var callbackOwner: BluetoothComponent? = null
    // 首次 ready 回调是否已触发（整个进程只做一次）
    private var didRefreshReadyOnce = false
  }
}
