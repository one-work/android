package com.xcprinter.app.bt

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
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
import kotlinx.serialization.Serializable

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
 * 蓝牙打印机管理器单例（对照 iOS Lib/BluetoothManager.swift）。
 *
 * 注意：Android 的 BLE 设备地址使用 MAC（如 "AA:BB:CC:DD:EE:FF"），
 * iOS 使用 UUID，服务端需区分平台。
 */
@SuppressLint("MissingPermission")
object BluetoothPrinterManager {
  private const val TAG = "BluetoothPrinterManager"
  private const val SCAN_DURATION_MS = 5_000L

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
  private var targetCharacteristic: BluetoothGattCharacteristic? = null
  private var connectInProgress = false
  
  /** 当前连接状态字符串（connect 事件用） */
  var connectionState: String = "disconnected"
    private set
  
  private var stateReceiver: BroadcastReceiver? = null

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

    val device = discoveredDevices.firstOrNull { it.address == address }
      ?: runCatching { adapter.getRemoteDevice(address) }.getOrNull()

    if (device == null) {
      dispatchOnMain { onConnected?.invoke(false) }
      return
    }

    closeGatt()
    connectInProgress = true
    connectionState = "connecting"
    gatt = device.connectGatt(appContext, false, gattCallback)
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
  
  // MARK: - Send Data（对照 iOS send）
  
  fun send(bytes: ByteArray) {
    val currentGatt = gatt
    val characteristic = targetCharacteristic
    if (currentGatt == null || characteristic == null || connectionState != "connected") {
      dispatchOnMain { onDataSent?.invoke(SendResultData(success = false, error = "未连接设备")) }
      return
    }
    
    val initiated = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      currentGatt.writeCharacteristic(
        characteristic,
        bytes,
        BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
      ) == BluetoothGatt.GATT_SUCCESS
    } else {
      @Suppress("DEPRECATION")
      characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
      @Suppress("DEPRECATION")
      characteristic.value = bytes
      @Suppress("DEPRECATION")
      currentGatt.writeCharacteristic(characteristic)
    }
    
    if (!initiated) {
      dispatchOnMain { onDataSent?.invoke(SendResultData(success = false, error = "写入失败")) }
    }
  }
  
  // GATT 回调
  private val gattCallback = object : BluetoothGattCallback() {
    override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
      when (newState) {
        BluetoothProfile.STATE_CONNECTED -> {
          connectInProgress = false
          connectionState = "connected"
          g.discoverServices()
          dispatchOnMain { onConnected?.invoke(true) }
        }
        BluetoothProfile.STATE_DISCONNECTED -> {
          val wasConnecting = connectInProgress
          connectInProgress = false
          connectionState = "disconnected"
          targetCharacteristic = null
          closeGatt()
          if (wasConnecting) {
            // 对照 iOS didFailToConnect
            dispatchOnMain { onConnected?.invoke(false) }
          } else {
            // 对照 iOS didDisconnectPeripheral
            dispatchOnMain { onDisconnected?.invoke(true) }
          }
        }
      }
    }
    
    override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
      // 取所有 service 中第一个带 WRITE 属性的 characteristic（对照 iOS didDiscoverCharacteristicsFor）
      targetCharacteristic = g.services
        .flatMap { it.characteristics }
        .firstOrNull {
          it.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0
        }
      if (targetCharacteristic == null) {
        Log.w(TAG, "未找到可写 characteristic")
      }
    }
    
    // 注意：SDK 的 BluetoothGattCallback 没有带 value 的 onCharacteristicWrite
    // 重载（只有 onCharacteristicRead 有），写入结果统一走这个回调。
    override fun onCharacteristicWrite(
      g: BluetoothGatt,
      characteristic: BluetoothGattCharacteristic,
      status: Int
    ) {
      handleWriteResult(status)
    }
    
    private fun handleWriteResult(status: Int) {
      if (status == BluetoothGatt.GATT_SUCCESS) {
        dispatchOnMain { onDataSent?.invoke(SendResultData(success = true, error = null)) }
      } else {
        dispatchOnMain { onDataSent?.invoke(SendResultData(success = false, error = "写入失败，状态码: $status")) }
      }
    }
  }
  
  private fun closeGatt() {
    runCatching { gatt?.close() }
    gatt = null
  }
  
  // MARK: - 权限检查
  
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
