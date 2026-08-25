package com.xcprinter.app.features.web

import android.os.Bundle
import android.view.View
import dev.hotwire.core.turbo.errors.VisitError
import dev.hotwire.navigation.destinations.HotwireDestinationDeepLink
import dev.hotwire.navigation.fragments.HotwireWebFragment

/**
 * 自定义 Web Fragment（对照 iOS Lib/WebViewController.swift）：
 * - 冷启动完成后注入 assets 中的 init_turbo.js / init.js
 * - /bluetooth/menus 页面：右上角「关闭」按钮；离开时刷新下层页面
 * - 401 错误路由到登录页
 */
@HotwireDestinationDeepLink(uri = "hotwire://fragment/web")
open class WebFragment : HotwireWebFragment() {

  override fun onStart() {
    // 跨域导航前强制 cold boot，必须在 super.onStart() 触发 visit 之前执行
    WebViewBehavior.resetSessionIfCrossOrigin(this)
    super.onStart()
  }

  override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    super.onViewCreated(view, savedInstanceState)
    WebViewBehavior.addModalCloseButtonIfNeeded(this)
  }

  override fun onDestroyView() {
    super.onDestroyView()
    WebViewBehavior.refreshUnderlyingDestinationOnLeave(this)
  }

  override fun onColdBootPageCompleted(location: String) {
    super.onColdBootPageCompleted(location)
    WebViewBehavior.injectScripts(navigator.session.webView)
  }

  override fun onVisitErrorReceived(location: String, error: VisitError) {
    if (WebViewBehavior.handleVisitError(this, error)) return
    super.onVisitErrorReceived(location, error)
  }
}
