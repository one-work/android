package com.xcprinter.app.bridge

import android.content.pm.PackageManager
import android.util.Log
import androidx.fragment.app.Fragment
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import dev.hotwire.core.bridge.BridgeComponent
import dev.hotwire.core.bridge.BridgeDelegate
import dev.hotwire.core.bridge.Message
import dev.hotwire.navigation.destinations.HotwireDestination
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 扫码组件（对照 iOS Bridge/ScanComponent.swift）。
 *
 * - `start` 事件：弹出全屏扫码界面
 * - 扫到后回复 {"value":"..."}；设备无相机时回复 {"value":null}
 * - 用户取消不回复（与 iOS 一致）
 *
 * 使用 zxing-android-embedded 的 ScanContract，通过 Activity 的
 * activityResultRegistry 注册 launcher（组件是消息到达时才创建的，
 * 不能用 fragment.registerForActivityResult）。
 * CaptureActivity 会自行请求 CAMERA 运行时权限。
 */
class ScanComponent(
    name: String,
    private val delegate: BridgeDelegate<HotwireDestination>
) : BridgeComponent<HotwireDestination>(name, delegate) {

    private val fragment: Fragment
        get() = delegate.destination.fragment

    override fun onReceive(message: Message) {
        when (message.event) {
            "start" -> startScan()
            else -> Log.w(TAG, "Unknown event for message: $message")
        }
    }

    private fun startScan() {
        val activity = fragment.activity ?: return

        // 设备不支持（无相机）时直接回复空结果
        // （手写 JSON：库默认 explicitNulls=false 会把 null 字段省略，
        //  而 iOS 回复的是 {"value":null}，契约需保持一致）
        val hasCamera = activity.packageManager
            .hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
        if (!hasCamera) {
            replyTo("start", """{"value":null}""")
            return
        }

        // 组件是消息到达时（fragment 已 RESUMED）才创建的，
        // 不能用带 LifecycleOwner 的 register（会抛异常），
        // 用无 LifecycleOwner 的版本，回调里通过 launcher.unregister() 手动释放。
        val registry = activity.activityResultRegistry
        val launcherKey = "scan_${System.currentTimeMillis()}"
        lateinit var launcher: androidx.activity.result.ActivityResultLauncher<ScanOptions>
        launcher = registry.register(launcherKey, ScanContract()) { result ->
            launcher.unregister()
            val value = result.contents
            if (value != null) {
                replyTo("start", ScanReply(value = value))
            }
            // 用户取消（contents == null）不回复，与 iOS 一致
        }

        val options = ScanOptions().apply {
            setDesiredBarcodeFormats(
                ScanOptions.QR_CODE,
                ScanOptions.EAN_13,
                ScanOptions.EAN_8,
                ScanOptions.CODE_128,
                ScanOptions.CODE_39,
                ScanOptions.UPC_E,
                ScanOptions.PDF_417
            )
            setPrompt("")
            setBeepEnabled(true)
            setOrientationLocked(false)
        }
        launcher.launch(options)
    }

    @Serializable
    private data class ScanReply(
        // 用 JSON 编码防止码值中包含引号导致 JSON 非法（对照 iOS JSONEncoder）
        @SerialName("value") val value: String
    )

    private companion object {
        const val TAG = "ScanComponent"
    }
}
