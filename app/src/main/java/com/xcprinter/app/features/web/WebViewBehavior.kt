package com.xcprinter.app.features.web

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.Menu
import android.view.MenuItem
import androidx.core.net.toUri
import com.xcprinter.app.R
import dev.hotwire.core.turbo.errors.HttpError
import dev.hotwire.core.turbo.errors.VisitError
import dev.hotwire.core.turbo.webview.HotwireWebView
import dev.hotwire.navigation.destinations.HotwireDestination

/**
 * WebFragment / WebBottomSheetFragment 共享的行为
 * （对照 iOS Lib/WebViewController.swift 与 AppDelegate 的 makeCustomWebView）。
 */
internal object WebViewBehavior {

  /** 与 iOS WebViewController 中判断 initialVisitableURL.path 一致 */
  fun isBluetoothMenusPage(location: String?): Boolean {
    return location?.toUri()?.path == "/bluetooth/menus"
  }

  // ------------------------------------------------------------------
  // 跨域导航修复：
  // Turbo 的 visit 通过 fetch 请求页面，跨域 fetch 会被 CORS 拦截，
  // 导致跨域页面无法打开（回调 onVisitErrorReceived，错误码 Unknown）。
  // 跨域场景必须让 WebView 整页冷启动加载（cold boot）。
  // 在 Fragment onStart() 时判断：WebView 当前停留的 URL 与本 destination
  // 的 location 不同源时，先 reset session（清除 isReady 标志），迫使本次
  // visit 走 visitLocationAsColdBoot（WebView.loadUrl 整页加载），
  // 绕开 Turbo fetch / CORS 限制。同域导航不受影响，仍走 Turbo 局部刷新。
  // （对照 iOS 用独立 ExternalWebSession 承载跨域页面的做法。）
  // ------------------------------------------------------------------

  fun resetSessionIfCrossOrigin(destination: HotwireDestination) {
    val session = destination.navigator.session
    val currentUrl = session.webView.url ?: return
    val targetUrl = destination.location

    if (isCrossOrigin(currentUrl, targetUrl)) {
      session.reset()
    }
  }

  private fun isCrossOrigin(first: String, second: String): Boolean {
    val firstUri = first.toUri()
    val secondUri = second.toUri()
    return firstUri.scheme != secondUri.scheme ||
        firstUri.host != secondUri.host ||
        firstUri.port != secondUri.port
  }

  // ------------------------------------------------------------------
  // JS 注入（对照 iOS 的 WKUserScript，atDocumentEnd）
  // Android 没有 WKUserScript，等价做法是在冷启动页面加载完成后注入。
  // 脚本自带防重复 guard（检测元素 id），重复注入安全。
  // ------------------------------------------------------------------
  private var cachedScripts: String? = null

  fun injectScripts(context: Context, webView: HotwireWebView) {
    val js = cachedScripts ?: readScripts(context).also { cachedScripts = it }
    if (js.isNotBlank()) {
      webView.evaluateJavascript(js, null)
    }
  }

  private fun readScripts(context: Context): String {
    return listOf("js/init_turbo.js", "js/init.js")
      .mapNotNull { path ->
        runCatching {
          context.assets.open(path).bufferedReader().use { it.readText() }
        }.getOrNull()
      }
      // 必须以分号分隔：各文件是无分号结尾的 IIFE，直接用 "\n" 拼接时
      // JS 不会自动断句（ASI），后一个文件的 "(" 会被解析成对前一个
      // IIFE 返回值的调用，抛 TypeError 导致后续脚本全部不执行。
      .joinToString(";\n")
  }

  // ------------------------------------------------------------------
  // 模态关闭按钮（对照 iOS addModalCloseButtonIfNeeded）：
  // 表单页（/bluetooth/menus）右上角放「关闭」(X)，点击返回。
  // ------------------------------------------------------------------

  fun addModalCloseButtonIfNeeded(destination: HotwireDestination) {
    if (!isBluetoothMenusPage(destination.location)) return
    
    val toolbar = destination.toolbarForNavigation() ?: return
    val item = toolbar.menu.add(Menu.NONE, CLOSE_MENU_ITEM_ID, Menu.NONE, R.string.close)
    item.setIcon(R.drawable.ic_close)
    item.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
    toolbar.setOnMenuItemClickListener {
      if (it.itemId == CLOSE_MENU_ITEM_ID) {
        destination.navigator.pop()
        true
      } else {
        false
      }
    }
  }

  // ------------------------------------------------------------------
  // 离开刷新（对照 iOS viewDidDisappear 中的 session.reload()）：
  // /bluetooth/menus 页面消失后，刷新回到的下层页面。
  // ------------------------------------------------------------------
  fun refreshUnderlyingDestinationOnLeave(destination: HotwireDestination) {
    if (!isBluetoothMenusPage(destination.location)) return
    
    // 等待返回栈弹出完成后再刷新当前（下层）destination
    Handler(Looper.getMainLooper()).post {
      runCatching {
        destination.navigator.currentDestination?.refresh(displayProgress = false)
      }
    }
  }
  
  // ------------------------------------------------------------------
  // 401 处理（对照 iOS SceneController 的 promptForAuthentication）
  // 返回 true 表示已处理（跳转登录页），false 表示交给默认错误页。
  // ------------------------------------------------------------------
  fun handleVisitError(destination: HotwireDestination, error: VisitError): Boolean {
    val statusCode = (error as? HttpError)?.statusCode
    if (statusCode != 401) return false
    
    destination.navigator.route("${com.xcprinter.app.Demo.current}/session/new")
    return true
  }

  private const val CLOSE_MENU_ITEM_ID = 0xC105E
}
