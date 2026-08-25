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

// 底部 Tab
val mainTabs: List<HotwireBottomTab>
  get() = listOf(home, help)
