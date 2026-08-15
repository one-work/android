package com.xcprinter.app.bridge

import android.util.Log
import dev.hotwire.core.bridge.BridgeComponent
import dev.hotwire.core.bridge.BridgeDelegate
import dev.hotwire.core.bridge.Message
import dev.hotwire.navigation.destinations.HotwireDestination
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Apple 登录组件（对照 iOS Bridge/AppleSignInComponent.swift）。
 *
 * Android 无系统级 Sign in with Apple，收到 signIn 事件时直接回复不支持。
 * 如需第三方登录，Android 上应改用 Credential Manager / Google Sign-In，
 * 需要服务端配合验证 Google token（详见 README「与 iOS 的差异」）。
 */
class AppleSignInComponent(
    name: String,
    private val delegate: BridgeDelegate<HotwireDestination>
) : BridgeComponent<HotwireDestination>(name, delegate) {

    override fun onReceive(message: Message) {
        when (message.event) {
            "signIn" -> replyTo(
                "signIn",
                UserData(
                    success = true,
                    cancelled = false,
                    error = "Android 暂不支持 Apple 登录，请使用其他登录方式"
                )
            )
            else -> Log.w(TAG, "Unknown event for message: $message")
        }
    }

    @Serializable
    private data class UserData(
        @SerialName("success") val success: Boolean,
        @SerialName("cancelled") val cancelled: Boolean,
        @SerialName("error") val error: String
    )

    private companion object {
        const val TAG = "AppleSignInComponent"
    }
}
