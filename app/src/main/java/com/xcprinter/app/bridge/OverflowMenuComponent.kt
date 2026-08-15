package com.xcprinter.app.bridge

import android.util.Log
import android.view.Menu
import android.view.MenuItem
import dev.hotwire.core.bridge.BridgeComponent
import dev.hotwire.core.bridge.BridgeDelegate
import dev.hotwire.core.bridge.Message
import dev.hotwire.navigation.destinations.HotwireDestination
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 右上角菜单项组件（对照 iOS Bridge/OverflowMenuComponent.swift）。
 *
 * `connect` 事件，data {"label":"...","url":"..."（可选）}：
 * toolbar 右侧显示菜单项（label 文字），点击时用 navigator 路由到 url，
 * url 缺省为 https://linlishenghuo.com/bluetooth/menus。
 */
class OverflowMenuComponent(
    name: String,
    private val delegate: BridgeDelegate<HotwireDestination>
) : BridgeComponent<HotwireDestination>(name, delegate) {

    private var formUrl: String? = null

    override fun onReceive(message: Message) {
        when (message.event) {
            "connect" -> handleConnectEvent(message)
            else -> Log.w(TAG, "Unknown event for message: $message")
        }
    }

    private fun handleConnectEvent(message: Message) {
        val data = message.data<MessageData>() ?: return
        data.url?.let { formUrl = it }
        showOverflowMenuItem(data)
    }

    private fun showOverflowMenuItem(data: MessageData) {
        val toolbar = delegate.destination.toolbarForNavigation() ?: return

        toolbar.menu.removeItem(MENU_ITEM_ID)
        val item = toolbar.menu.add(Menu.NONE, MENU_ITEM_ID, Menu.NONE, data.label)
        item.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)

        toolbar.setOnMenuItemClickListener {
            if (it.itemId == MENU_ITEM_ID) {
                performClick()
                true
            } else {
                false
            }
        }
    }

    private fun performClick() {
        delegate.destination.navigator.route(formUrl ?: DEFAULT_FORM_URL)
    }

    @Serializable
    private data class MessageData(
        @SerialName("label") val label: String,
        @SerialName("url") val url: String? = null
    )

    private companion object {
        const val TAG = "OverflowMenuComponent"
        const val MENU_ITEM_ID = 0xF10E7
        const val DEFAULT_FORM_URL = "https://linlishenghuo.com/bluetooth/menus"
    }
}
