package com.xcprinter.app.bt

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.xcprinter.app.BuildConfig
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class DeviceInfo(
  val name: String? = null,
  val address: String
)

data class SendResultData(
  val success: Boolean,
  val error: String?
)

/**
 * 蓝牙打印机管理器单例
 *
 * 数据通道采用「MTU + Credits 双重流控」：
 * - 下行：所有业务数据通过 FF02 以 WRITE_TYPE_NO_RESPONSE 写入，避免链路层逐包 ACK；
 * - 上行：FF03 Notify 推送 MTU 协商结果（0x02 + 小端 16bit 包长）与 Credits 配额
 *   （0x01 + 增量配额）；
 * - 流控：credits 初始为 0，收到 Credits 通知前不下行业务数据；每写出一包 credits-1，
 *   耗尽即挂起，下一帧 Credits 到达后恢复；任意时刻仅一包在途，保证按入队顺序到达。
 *
 * 注意：Android 的 BLE 设备地址使用 MAC（如 "AA:BB:CC:DD:EE:FF"），
 * iOS 使用 UUID，服务端需区分平台。
 */
@SuppressLint("MissingPermission")
object BluetoothPrinterManager {
  private const val TAG = "BluetoothPrinterManager"
  private const val SCAN_DURATION_MS = 5_000L
  /** 应用层连接超时兜底（autoConnect=true 时系统不会自行超时） */
  private const val CONNECT_TIMEOUT_MS = 30_000L
  /** 期望协商的链路层 MTU（对照 wx.setBLEMTU({mtu: 247})），有效载荷 = MTU - 3（ATT 头） */
  private const val REQUEST_MTU = 247
  /** 协商前的保守单包大小（默认 ATT MTU 23 - 3） */
  private const val DEFAULT_PACKET_SIZE = 20
  /** 启用 FF03 通知后等待首帧 Credits 的提示超时（仅日志提示，不阻断） */
  private const val CREDITS_TIMEOUT_MS = 5_000L
  // FF03 通知包类型标识（首字节）
  private const val NOTIFY_TYPE_CREDITS: Byte = 0x01
  private const val NOTIFY_TYPE_MTU: Byte = 0x02
  // 打印机 GATT 服务/特征值（对照小程序服务清单）
  private val SERVICE_UUID: UUID = UUID.fromString("0000FF00-0000-1000-8000-00805F9B34FB")
  /** 下行数据通道（Write No Response） */
  private val WRITE_CHAR_UUID: UUID = UUID.fromString("0000FF02-0000-1000-8000-00805F9B34FB")
  /** 上行流控通知通道（Notify：MTU 协商结果 + Credits 配额） */
  private val NOTIFY_CHAR_UUID: UUID = UUID.fromString("0000FF03-0000-1000-8000-00805F9B34FB")
  private val CCC_DESCRIPTOR_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")

  // Callbacks
  var onReady: ((Boolean) -> Unit)? = null
  var onDeviceFound: ((DeviceInfo) -> Unit)? = null
  var onConnected: ((Boolean) -> Unit)? = null
  var onDisconnected: ((Boolean) -> Unit)? = null
  var onDataSent: ((SendResultData) -> Unit)? = null

  // Properties
  private lateinit var appContext: Context
  private val handler = Handler(Looper.getMainLooper())

  /**
   * 统一在主线程派发对外回调。
   * BLE 系统回调（ScanCallback / BluetoothGattCallback）在 binder 线程触发，
   * 而回调消费方（Bridge 组件 replyTo -> WebView.evaluateJavascript）必须在主线程执行，
   * 否则抛 WebViewMethodCalledOnWrongThreadViolation。
   */
  private fun dispatchOnMain(block: () -> Unit) {
    if (Looper.myLooper() == Looper.getMainLooper()) block() else handler.post(block)
  }

  private val bluetoothAdapter: BluetoothAdapter? by lazy {
    appContext.getSystemService(BluetoothManager::class.java)?.adapter
  }

  private val discoveredDevices = mutableListOf<BluetoothDevice>()
  private var gatt: BluetoothGatt? = null
  private var writeCharacteristic: BluetoothGattCharacteristic? = null
  private var notifyCharacteristic: BluetoothGattCharacteristic? = null
  private var connectInProgress = false

  /** 当前连接状态字符串（connect 事件用） */
  @Volatile
  var connectionState: String = "disconnected"
    private set

  private var stateReceiver: BroadcastReceiver? = null
  // ---- 流控状态----

  /** 流控状态锁：GATT 回调在 binder 线程，send 在桥接线程 */
  private val flowLock = Any()
  /** 单包最大字节：先由 MTU 协商临时设定，后以 FF03 MTU 通知为准 */
  @Volatile
  private var packetSize = DEFAULT_PACKET_SIZE
  /** 当前可用发送配额：协议规定初始为 0，必须等待 FF03 Credits 通知 */
  private var credits = 0
  /** 是否已收到过至少一次 Credits 通知 */
  private var creditsReceived = false
  /** 是否已完成 MTU 协商（协商成功或收到 FF03 MTU 通知任一发生即置位） */
  private var mtuNegotiated = false
  /** 待发送分包队列（FIFO） */
  private val sendQueue = ArrayDeque<ByteArray>()
  /** 是否单次写入在途（严格串行互斥，防止并发乱序） */
  private var isWriting = false
  /** 每个 send() 调用对应一条记录，remaining 为尚未写出的包数，归零时回调成功 */
  private class PendingSend(var remaining: Int)
  private val pendingSends = ArrayDeque<PendingSend>()
  /** 5 秒未收到 Credits 仅提示不阻断（兼容首帧延迟大的机型，对照 ble.js 5s 定时器） */
  private val creditsTimeoutRunnable = Runnable {
    if (!creditsReceived) {
      Log.w(TAG, "启用 FF03 通知 ${CREDITS_TIMEOUT_MS}ms 后仍未收到 Credits，请检查打印机连接")
    }
  }

  fun initialize(context: Context) {
    appContext = context.applicationContext
    registerStateReceiver()
  }

  // Ready（对照 iOS centralManagerDidUpdateState）
  private fun registerStateReceiver() {
    if (stateReceiver != null) return

    val receiver = object : BroadcastReceiver() {
      override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
        when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
          BluetoothAdapter.STATE_ON -> {
            Log.d(TAG, "蓝牙已开启")
            dispatchOnMain { onReady?.invoke(true) }
          }
          BluetoothAdapter.STATE_OFF -> Log.d(TAG, "蓝牙已关闭")
        }
      }
    }
    appContext.registerReceiver(receiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
    stateReceiver = receiver
  }

  /**
   * 组件设置好 onReady 回调后调用：若蓝牙已开启立即回调 true
   * （对照 iOS CBCentralManager 初始化时立即触发 didUpdateState）。
   */
  fun refreshReadyState() {
    // API 31+ 调用 isEnabled 需要 BLUETOOTH_CONNECT 权限，否则抛 SecurityException
    if (hasBluetoothPermissions() && bluetoothAdapter?.isEnabled == true) {
      dispatchOnMain { onReady?.invoke(true) }
    }
  }

  // Scan
  @Volatile
  private var isScanning = false
  private val stopScanRunnable = Runnable { stopScan() }

  /** 连接超时：取消挂起的连接并上报失败 */
  private val connectTimeoutRunnable = Runnable {
    if (!connectInProgress) return@Runnable
    Log.w(TAG, "连接超时（${CONNECT_TIMEOUT_MS}ms），取消连接")
    connectInProgress = false
    connectionState = "disconnected"
    closeGatt()
    dispatchOnMain { onConnected?.invoke(false) }
  }

  fun startScan() {
    val scanner = bluetoothAdapter?.bluetoothLeScanner
    if (scanner == null || !hasBluetoothPermissions()) {
      Log.w(TAG, "无法扫描：蓝牙不可用或缺少权限")
      return
    }

    discoveredDevices.clear()
    stopScan()

    try {
      scanner.startScan(scanCallback)
      isScanning = true
      handler.postDelayed(stopScanRunnable, SCAN_DURATION_MS)
    } catch (e: IllegalStateException) {
      // 蓝牙在扫描启动瞬间被关闭时系统会抛 IllegalStateException
      Log.w(TAG, "启动扫描失败", e)
    }
  }

  private fun stopScan() {
    if (!isScanning) return
    isScanning = false
    handler.removeCallbacks(stopScanRunnable)
    if (hasBluetoothPermissions()) {
      runCatching { bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback) }
    }
  }

  private val scanCallback = object : ScanCallback() {
    override fun onScanResult(callbackType: Int, result: ScanResult) {
      val device = result.device
      val name = runCatching { device.name }.getOrNull() ?: return
      if (discoveredDevices.none { it.address == device.address }) {
        discoveredDevices.add(device)
        val info = DeviceInfo(name = name, address = device.address)
        dispatchOnMain { onDeviceFound?.invoke(info) }
      }
    }
  }

  // Connect
  fun connect(address: String) {
    val adapter = bluetoothAdapter
    if (adapter == null || !hasBluetoothPermissions()) {
      dispatchOnMain { onConnected?.invoke(false) }
      return
    }

    val scannedDevice = discoveredDevices.firstOrNull { it.address == address }
    val device = scannedDevice ?: runCatching { adapter.getRemoteDevice(address) }.getOrNull()

    if (device == null) {
      dispatchOnMain { onConnected?.invoke(false) }
      return
    }

    // 扫描与连接并发会显著降低建链成功率，连接前先停止扫描
    stopScan()
    closeGatt()
    resetFlowControl()
    connectInProgress = true
    connectionState = "connecting"
    // 显式指定 TRANSPORT_LE（默认 TRANSPORT_AUTO 对纯 BLE 设备在部分机型上建链容易失败）。
    // 本次扫描到的设备用直连（autoConnect=false，约 20~30 秒内快速反馈）；
    // 仅凭历史 MAC 重连的设备用 autoConnect=true，等待设备下次广播再建链，由应用层超时兜底。
    val autoConnect = scannedDevice == null
    Log.d(TAG, "连接设备 $address（autoConnect=$autoConnect, transport=LE）")
    gatt = device.connectGatt(appContext, autoConnect, gattCallback, BluetoothDevice.TRANSPORT_LE)
    handler.removeCallbacks(connectTimeoutRunnable)
    handler.postDelayed(connectTimeoutRunnable, CONNECT_TIMEOUT_MS)
  }

  // Disconnect
  fun disconnect(address: String) {
    val current = gatt ?: run {
      dispatchOnMain { onDisconnected?.invoke(true) }
      return
    }
    connectionState = "disconnecting"
    current.disconnect()
  }

  // Send Data
  /**
   * 发送业务数据（对照小程序 ble.send）：按 packetSize 切片入队，由 Credits
   * 流控驱动经 FF02 writeNoResponse 串行写出。数据先排队，收到打印机 Credits
   * 后自动发出；onDataSent 在本次调用的全部数据写完后回调一次。
   *
   * 注意：运行期间若收到新的 FF03 MTU 通知，仅影响后续 send 调用的切片，
   * 已入队的旧包不重新切分（与小程序行为一致）。
   */
  fun send(bytes: ByteArray) {
    if (gatt == null || writeCharacteristic == null || connectionState != "connected") {
      dispatchOnMain { onDataSent?.invoke(SendResultData(success = false, error = "未连接设备")) }
      return
    }
    if (bytes.isEmpty()) {
      dispatchOnMain { onDataSent?.invoke(SendResultData(success = true, error = null)) }
      return
    }

    val size = packetSize.coerceAtLeast(1)
    synchronized(flowLock) {
      var count = 0
      var offset = 0
      while (offset < bytes.size) {
        val end = (offset + size).coerceAtMost(bytes.size)
        sendQueue.addLast(bytes.copyOfRange(offset, end))
        offset = end
        count++
      }
      pendingSends.addLast(PendingSend(count))
      Log.d(TAG, "入队 ${bytes.size} 字节（$count 包，packetSize=$size，credits=$credits）")
    }
    flushQueue()
  }

  /**
   * 冲刷发送队列：
   * credits<=0 时挂起，等待 FF03 Credits 通知恢复；任意时刻仅一包在途，
   * 写完成回调驱动下一包，保证包按入队顺序到达打印机。
   */
  private fun flushQueue() {
    val g: BluetoothGatt
    val ch: BluetoothGattCharacteristic
    val packet: ByteArray
    synchronized(flowLock) {
      if (isWriting) return
      if (connectionState != "connected") return
      g = gatt ?: return
      ch = writeCharacteristic ?: return
      if (sendQueue.isEmpty()) return
      if (credits <= 0) {
        Log.d(TAG, "credits 耗尽，挂起发送（队列剩余 ${sendQueue.size} 包）")
        return
      }
      packet = sendQueue.first()
      isWriting = true
    }

    if (!writeNoResponse(g, ch, packet)) {
      Log.w(TAG, "写入发起失败")
      failPendingSends("写入失败")
    }
  }

  /**
   * 以 WRITE_TYPE_NO_RESPONSE 写入 FF02（对照小程序 writeNoResponse）：
   * 应用层流控已由 FF03 Credits 保证，无需链路层逐包 ACK，吞吐显著更高。
   */
  private fun writeNoResponse(
    g: BluetoothGatt,
    ch: BluetoothGattCharacteristic,
    data: ByteArray
  ): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      g.writeCharacteristic(ch, data, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) ==
        BluetoothStatusCodes.SUCCESS
    } else {
      @Suppress("DEPRECATION")
      ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
      @Suppress("DEPRECATION")
      ch.value = data
      @Suppress("DEPRECATION")
      g.writeCharacteristic(ch)
    }

  /**
   * 写入失败/断开时清空发送队列并向上层报错（每条 pending 记录回调一次）。
   * 注：小程序文档为「包重入队首、resolve 交上层重试」；桥接层每条 send_data
   * 消息需明确答复，故直接报错由 JS 决定重发。
   */
  private fun failPendingSends(error: String) {
    val count: Int
    synchronized(flowLock) {
      sendQueue.clear()
      isWriting = false
      count = pendingSends.size
      pendingSends.clear()
    }
    if (count > 0) {
      dispatchOnMain { repeat(count) { onDataSent?.invoke(SendResultData(success = false, error = error)) } }
    }
  }

  /** 断开后复位全部流控状态（对照小程序 resetState） */
  private fun resetFlowControl() {
    synchronized(flowLock) {
      sendQueue.clear()
      pendingSends.clear()
      isWriting = false
      credits = 0
    }
    creditsReceived = false
    mtuNegotiated = false
    packetSize = DEFAULT_PACKET_SIZE
    writeCharacteristic = null
    notifyCharacteristic = null
    handler.removeCallbacks(creditsTimeoutRunnable)
  }

  // GATT 回调
  private val gattCallback = object : BluetoothGattCallback() {
    override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
      Log.d(TAG, "onConnectionStateChange: status=$status, newState=$newState")
      handler.removeCallbacks(connectTimeoutRunnable)
      when (newState) {
        BluetoothProfile.STATE_CONNECTED -> {
          connectInProgress = false
          connectionState = "connected"
          // 主动协商大 MTU（对照 wx.setBLEMTU(247)），成功后在 onMtuChanged 枚举服务
          Log.d(TAG, "请求 MTU=$REQUEST_MTU")
          if (!g.requestMtu(REQUEST_MTU)) {
            Log.w(TAG, "requestMtu 发起失败，直接枚举服务")
            g.discoverServices()
          }
          dispatchOnMain { onConnected?.invoke(true) }
        }
        BluetoothProfile.STATE_DISCONNECTED -> {
          val wasConnecting = connectInProgress
          val previousState = connectionState
          connectInProgress = false
          connectionState = "disconnected"
          failPendingSends("连接已断开")
          resetFlowControl()
          closeGatt()
          when {
            // 对照 iOS didFailToConnect
            wasConnecting -> dispatchOnMain { onConnected?.invoke(false) }
            // 连接前 closeGatt 或超时取消触发的收尾回调，忽略避免误报断开
            previousState == "disconnected" -> Unit
            // 对照 iOS didDisconnectPeripheral
            else -> dispatchOnMain { onDisconnected?.invoke(true) }
          }
        }
      }
    }

    override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
      if (status == BluetoothGatt.GATT_SUCCESS) {
        // 有效载荷 = MTU - 3（ATT 头），作为 FF03 MTU 通知到达前的临时切片长度
        packetSize = (mtu - 3).coerceAtLeast(DEFAULT_PACKET_SIZE)
        mtuNegotiated = true
        Log.d(TAG, "MTU 协商成功：mtu=$mtu, packetSize=$packetSize")
      } else {
        Log.w(TAG, "MTU 协商失败（status=$status），保持默认包大小 $DEFAULT_PACKET_SIZE")
      }
      g.discoverServices()
    }

    override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
      if (BuildConfig.DEBUG) {
        g.services.flatMap { it.characteristics }.forEach { c ->
          Log.d(TAG, "发现 characteristic: uuid=${c.uuid}, service=${c.service.uuid}, properties=${c.propertiesToString()}")
        }
      }

      val service = g.getService(SERVICE_UUID)
      val write = service?.getCharacteristic(WRITE_CHAR_UUID)
      val notify = service?.getCharacteristic(NOTIFY_CHAR_UUID)
      if (service == null || write == null || notify == null) {
        // 服务/特征值缺失：主动断开物理连接，避免占用蓝牙资源（对照小程序 _forceDisconnect）
        Log.w(TAG, "GATT 枚举不完整（ff00=${service != null}, ff02=${write != null}, ff03=${notify != null}），判定非目标设备，断开连接")
        g.disconnect()
        return
      }

      writeCharacteristic = write
      notifyCharacteristic = notify
      enableFlowControlNotifications(g, notify)
    }

    // 注意：SDK 的 BluetoothGattCallback 没有带 value 的 onCharacteristicWrite
    // 重载（只有 onCharacteristicRead 有），写入结果统一走这个回调。
    override fun onCharacteristicWrite(
      g: BluetoothGatt,
      characteristic: BluetoothGattCharacteristic,
      status: Int
    ) {
      if (status != BluetoothGatt.GATT_SUCCESS) {
        Log.w(TAG, "写入失败，状态码: $status")
        failPendingSends("写入失败，状态码: $status")
        return
      }

      // writeNoResponse 的成功回调表示包已交付控制器队列：credits-1 并驱动下一包
      synchronized(flowLock) {
        credits = (credits - 1).coerceAtLeast(0)
        if (sendQueue.isNotEmpty()) sendQueue.removeFirst()
        isWriting = false
        val pending = pendingSends.firstOrNull()
        if (pending != null) {
          pending.remaining--
          if (pending.remaining <= 0) {
            pendingSends.removeFirst()
            dispatchOnMain { onDataSent?.invoke(SendResultData(success = true, error = null)) }
          }
        }
      }
      flushQueue()
    }

    // FF03 上行流控通知（对照小程序 onBLECharacteristicValueChange）。
    // 只重写旧版两参回调：框架默认的新版三参实现会转发到这里，各版本系统均可收到。
    @Deprecated("Deprecated in Java")
    override fun onCharacteristicChanged(
      g: BluetoothGatt,
      characteristic: BluetoothGattCharacteristic
    ) {
      if (characteristic.uuid != NOTIFY_CHAR_UUID) return
      @Suppress("DEPRECATION")
      val value = characteristic.value ?: return
      if (value.isEmpty()) return

      when (value[0]) {
        // (a) MTU 通知包：0x02 + 小端 16bit 单包最大字节数，覆盖协商值
        NOTIFY_TYPE_MTU -> if (value.size >= 3) {
          val size = (value[1].toInt() and 0xFF) or ((value[2].toInt() and 0xFF) shl 8)
          packetSize = size
          mtuNegotiated = true
          Log.d(TAG, "FF03 MTU 通知：packetSize=$size")
        }
        // (b) Credits 通知包：0x01 + 增量配额，credits += N 并恢复挂起的发送
        NOTIFY_TYPE_CREDITS -> if (value.size >= 2) {
          val delta = value[1].toInt() and 0xFF
          val first = !creditsReceived
          creditsReceived = true
          handler.removeCallbacks(creditsTimeoutRunnable)
          synchronized(flowLock) { credits += delta }
          if (first) Log.d(TAG, "收到首个 Credits（+$delta），开始下发数据")
          flushQueue()
        }
      }
    }
  }

  /** 启用 FF03 通知（MTU + Credits 流控通道），并启动 5 秒 Credits 等待提示 */
  private fun enableFlowControlNotifications(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
    if (!g.setCharacteristicNotification(ch, true)) {
      Log.w(TAG, "setCharacteristicNotification(FF03) 失败")
      return
    }
    val cccd = ch.getDescriptor(CCC_DESCRIPTOR_UUID)
    if (cccd == null) {
      Log.w(TAG, "FF03 缺少 CCC descriptor，无法启用通知")
      return
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
    } else {
      @Suppress("DEPRECATION")
      cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
      @Suppress("DEPRECATION")
      g.writeDescriptor(cccd)
    }
    handler.removeCallbacks(creditsTimeoutRunnable)
    handler.postDelayed(creditsTimeoutRunnable, CREDITS_TIMEOUT_MS)
  }

  private fun closeGatt() {
    runCatching { gatt?.close() }
    gatt = null
  }

  /** 将 characteristic 的 properties 位掩码解码为可读字符串（调试用） */
  private fun BluetoothGattCharacteristic.propertiesToString(): String {
    val flags = mutableListOf<String>()
    if (properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) flags += "WRITE"
    if (properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) flags += "WRITE_NO_RESPONSE"
    if (properties and BluetoothGattCharacteristic.PROPERTY_READ != 0) flags += "READ"
    if (properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) flags += "NOTIFY"
    return flags.joinToString("|")
  }

  // 权限检查
  /** 按系统版本返回所需的蓝牙运行时权限 */
  fun requiredPermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      arrayOf(
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT
      )
    } else {
      arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

  fun hasBluetoothPermissions(): Boolean {
    if (!::appContext.isInitialized) return false

    return requiredPermissions().all {
      ContextCompat.checkSelfPermission(appContext, it) == PackageManager.PERMISSION_GRANTED
    }
  }
}
