package com.xcprinter.app

import android.app.Application
import dev.hotwire.core.bridge.BridgeComponentFactory
import dev.hotwire.core.bridge.KotlinXJsonConverter
import dev.hotwire.core.config.Hotwire
import dev.hotwire.core.logging.HotwireLogLevel
import dev.hotwire.core.turbo.config.PathConfiguration
import dev.hotwire.navigation.config.defaultFragmentDestination
import dev.hotwire.navigation.config.registerBridgeComponents
import dev.hotwire.navigation.config.registerFragmentDestinations
import dev.hotwire.navigation.config.registerRouteDecisionHandlers
import dev.hotwire.navigation.routing.AppNavigationRouteDecisionHandler
import dev.hotwire.navigation.routing.SystemNavigationRouteDecisionHandler
import com.xcprinter.app.bridge.AppleSignInComponent
import com.xcprinter.app.bridge.BluetoothComponent
import com.xcprinter.app.bridge.OverflowMenuComponent
import com.xcprinter.app.bridge.RightButtonComponent
import com.xcprinter.app.bridge.ScanComponent
import com.xcprinter.app.bt.BluetoothPrinterManager
import com.xcprinter.app.features.web.WebBottomSheetFragment
import com.xcprinter.app.features.web.WebFragment
import com.xcprinter.app.routing.ExternalRouteDecisionHandler

class XcPrinterApplication : Application() {
  override fun onCreate() {
    super.onCreate()
    configureHotwire()
    BluetoothPrinterManager.initialize(this)
  }

  private fun configureHotwire() {
    // Path configuration：本地 asset + 远程双来源（对照 iOS AppDelegate）
    Hotwire.loadPathConfiguration(
      context = this,
      location = PathConfiguration.Location(
        assetFilePath = "path-configuration.json",
        remoteFileUrl = "https://linlishenghuo.com/configurations/android_v1.json"
      )
    )

    // 默认 Fragment destination
    Hotwire.defaultFragmentDestination = WebFragment::class

    // 注册自定义 Fragment destinations
    Hotwire.registerFragmentDestinations(
      WebFragment::class,
      WebBottomSheetFragment::class
    )

    // 注册桥接组件（与 iOS 同名）
    Hotwire.registerBridgeComponents(
      BridgeComponentFactory("scan", ::ScanComponent),
      BridgeComponentFactory("bluetooth", ::BluetoothComponent),
      BridgeComponentFactory("right-button", ::RightButtonComponent),
      BridgeComponentFactory("apple-sign-in", ::AppleSignInComponent),
      BridgeComponentFactory("overflow-menu", ::OverflowMenuComponent)
    )

    // 路由决策：
    // 1. external —— 所有 http/https 链接（含跨域）都在 App 内原生打开
    // 2. system-navigation —— mailto:、tel: 等非 http/https 交给系统
    // 3. app-navigation —— 库内置同域导航兜底
    Hotwire.registerRouteDecisionHandlers(
      ExternalRouteDecisionHandler(),
      SystemNavigationRouteDecisionHandler(),
      AppNavigationRouteDecisionHandler()
    )

    // 配置项（对照 iOS AppDelegate）
    Hotwire.config.applicationUserAgentPrefix = "Hotwire"
    Hotwire.config.webViewDebuggingEnabled = true
    Hotwire.config.jsonConverter = KotlinXJsonConverter()

    // 开启 Hotwire 内部日志（调试跨域导航问题，tag: Hotwire-Core / Hotwire-Navigation）
    Hotwire.config.logger.logLevel = HotwireLogLevel.DEBUG
  }
}
