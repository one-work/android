package com.xcprinter.app.main

import com.xcprinter.app.Demo
import com.xcprinter.app.R
import dev.hotwire.navigation.navigator.NavigatorConfiguration
import dev.hotwire.navigation.tabs.HotwireBottomTab

private val home = HotwireBottomTab(
    title = "Home",
    iconResId = R.drawable.ic_tab_home,
    configuration = NavigatorConfiguration(
        name = "home",
        navigatorHostId = R.id.home_navigator_host,
        startLocation = "${Demo.current}/auth/apps"
    )
)

private val help = HotwireBottomTab(
    title = "Help",
    iconResId = R.drawable.ic_tab_help,
    configuration = NavigatorConfiguration(
        name = "help",
        navigatorHostId = R.id.help_navigator_host,
        startLocation = "https://app-demo.xcprinter.com/README"
    )
)

private val bugsAndFixes = HotwireBottomTab(
    title = "Bugs & Fixes",
    iconResId = R.drawable.ic_tab_bugs,
    configuration = NavigatorConfiguration(
        name = "bugs-fixes",
        navigatorHostId = R.id.bugs_navigator_host,
        startLocation = "${Demo.current}/bugs"
    )
)

/**
 * 底部 Tab（对照 iOS Lib/Tabs.swift）：
 * 仅当连接本地开发服务器（Demo.current == Demo.local）时追加 "Bugs & Fixes"。
 */
val mainTabs: List<HotwireBottomTab>
    get() = buildList {
        add(home)
        add(help)
        if (Demo.isLocal) {
            add(bugsAndFixes)
        }
    }
