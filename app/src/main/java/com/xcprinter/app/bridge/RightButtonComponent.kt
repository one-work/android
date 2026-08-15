package com.xcprinter.app.bridge

import android.view.Menu
import android.view.MenuItem
import dev.hotwire.core.bridge.BridgeComponent
import dev.hotwire.core.bridge.BridgeDelegate
import dev.hotwire.core.bridge.Message
import dev.hotwire.navigation.destinations.HotwireDestination
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 右上角文字按钮组件（对照 iOS Bridge/RightButtonComponent.swift）。
 *
 * 任意事件，data {"title":"..."}：在当前 fragment toolbar 右侧放一个
 * 文字按钮，点击时回复 "connect"（不带数据）。
 */
class RightButtonComponent(
    name: String,
    private val delegate: BridgeDelegate<HotwireDestination>
) : BridgeComponent<HotwireDestination>(name, delegate) {

    override fun onReceive(message: Message) {
        val data = message.data<MessageData>() ?: return
        addButton(data)
    }

    private fun addButton(data: MessageData) {
        val toolbar = delegate.destination.toolbarForNavigation() ?: return

        // 幂等：先移除旧按钮再添加
        toolbar.menu.removeItem(MENU_ITEM_ID)
        val item = toolbar.menu.add(Menu.NONE, MENU_ITEM_ID, Menu.NONE, data.title)
        item.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)

        toolbar.setOnMenuItemClickListener {
            if (it.itemId == MENU_ITEM_ID) {
                replyTo("connect")
                true
            } else {
                false
            }
        }
    }

    @Serializable
    private data class MessageData(
        @SerialName("title") val title: String
    )

    private companion object {
        const val MENU_ITEM_ID = 0xB1770
    }
}
