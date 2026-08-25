package com.xcprinter.app.features.web

import android.os.Bundle
import android.view.View
import dev.hotwire.core.turbo.errors.VisitError
import dev.hotwire.navigation.destinations.HotwireDestinationDeepLink
import dev.hotwire.navigation.fragments.HotwireWebBottomSheetFragment

/**
 * Bottom sheet 版本的 Web Fragment，行为与 [WebFragment] 一致
 * （对照 iOS 中以 bottom sheet 呈现的 WebViewController）。
 */
@HotwireDestinationDeepLink(uri = "hotwire://fragment/web/modal/sheet")
class WebBottomSheetFragment : HotwireWebBottomSheetFragment() {

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
