package com.xcprinter.app.routing

import androidx.core.net.toUri
import androidx.core.os.bundleOf
import dev.hotwire.core.config.Hotwire
import dev.hotwire.core.turbo.config.PathConfigurationProperties
import dev.hotwire.core.turbo.nav.PresentationContext
import dev.hotwire.core.turbo.visit.VisitProposal
import dev.hotwire.navigation.activities.HotwireActivity
import dev.hotwire.navigation.destinations.HotwireDestinationAnimations
import dev.hotwire.navigation.navigator.Navigator
import dev.hotwire.navigation.navigator.NavigatorConfiguration
import dev.hotwire.navigation.routing.Router

/**
 * 让所有 http/https 链接（包括跨域）都在 App 内原生打开，而不是跳系统浏览器
 * （对照 iOS Lib/ExternalDecisionHandler.swift 的 ExternalRouteDecisionHandler）。
 *
 * iOS 用一个独立的 ExternalWebSession 承载跨域页面；Android 上等价的做法是
 * 返回 [Router.Decision.NAVIGATE]，由同一 session 冷启动加载新域名页面，
 * 用户可见行为一致。
 *
 * 只匹配 http/https scheme，避免 mailto:、tel: 等被错误拦截；
 * 非 http/https 链接由系统 handler（SystemNavigationRouteDecisionHandler）处理。
 */
class ExternalRouteDecisionHandler : Router.RouteDecisionHandler {
  override val name = "external"

  override fun matches(proposal: VisitProposal, configuration: NavigatorConfiguration): Boolean {
    val scheme = proposal.location.toUri().scheme
    return scheme == "http" || scheme == "https"
  }

  override fun handle(
    proposal: VisitProposal,
    configuration: NavigatorConfiguration,
    activity: HotwireActivity
  ): Router.Decision {
    // ------------------------------------------------------------------
    // 修复跨域根页面无法打开的 bug（hotwire-native-android 1.3.1 的限制）：
    //
    // 远程 path configuration 的根路径规则（patterns: "^$" / "^/$"）会给
    // https://app-demo.xcprinter.com/ 这类跨域根链接附加
    // presentation=clear_all。当当前 navigator 处于 start destination
    // （返回栈只有一页，如 home tab 的 /auth/apps）时，
    // NavigatorRule.newPresentation() 会把 clear_all 降级为
    // Presentation.NONE（"You cannot clear all from the start destination,
    // prevent visit"），NavigatorMode.NONE 导致整个 visit 被静默吞掉：
    // 页面不打开、没有任何回调，表现为点击无反应。
    // （同域根链接在同样条件下也会被吞，这里一并修复。）
    //
    // 此时栈里只有一页，clear_all 无栈可清，语义上等价于直接打开新页面，
    // 因此手动以 PUSH 方式完成导航，并返回 CANCEL 阻止库再处理。
    // 跨域页面打开时由 WebViewBehavior.resetSessionIfCrossOrigin 负责
    // reset session 强制 cold boot，绕开 Turbo fetch 的 CORS 限制。
    // ------------------------------------------------------------------
    if (proposal.properties["presentation"] != "clear_all") {
      return Router.Decision.NAVIGATE
    }

    val navigator = activity.delegate
      .findNavigatorHost(configuration.navigatorHostId)
      ?.navigator
      ?: return Router.Decision.NAVIGATE

    if (!navigator.isAtStartDestination()) {
      return Router.Decision.NAVIGATE
    }

    return if (navigateManually(navigator, configuration, proposal)) {
      Router.Decision.CANCEL // 已手动完成导航，阻止库再处理本次 proposal
    } else {
      Router.Decision.NAVIGATE
    }
  }

  /**
   * 复制库内部 Navigator.navigateWithinContext → navigateToLocation 的
   * PUSH 行为（Navigator/NavigatorRule 的关键部分是 internal，无法直接复用）。
   * 返回 false 表示无法手动导航，回退给库默认处理。
   */
  private fun navigateManually(
    navigator: Navigator,
    configuration: NavigatorConfiguration,
    proposal: VisitProposal
  ): Boolean {
    val currentDestination = navigator.currentDestination ?: return false
    val navController = navigator.host.navController
    val location = proposal.location

    // path configuration 规则给出的目标 destination（hotwire://fragment/web）
    val destinationUri = (proposal.properties["uri"] as? String)?.toUri()
      ?: (proposal.properties["fallback_uri"] as? String)?.toUri()
      ?: return false
    val destination = navController.graph.find { it.hasDeepLink(destinationUri) }
      ?: return false

    // 与库内部 NavigatorRule.withNavArguments() 保持一致：
    // 目标 Fragment 通过 arguments 中这些 key 读取 location 等信息
    val arguments = bundleOf(
      ARG_LOCATION to location,
      ARG_NAVIGATOR_NAME to configuration.name,
      ARG_PRESENTATION_CONTEXT to PresentationContext.DEFAULT.name
    )

    // 使用与库一致的默认转场动画；newPathProperties 去掉 presentation=clear_all
    // 以使用常规的右滑进入动画（clear_all 自带的是"返回"动画，用于清空栈场景）
    val navOptions = HotwireDestinationAnimations.defaultNavOptions(
      currentPathProperties = Hotwire.config.pathConfiguration
        .properties(currentDestination.location),
      newPathProperties = PathConfigurationProperties(proposal.properties)
        .apply { remove("presentation") },
      action = proposal.options.action
    )

    // 先 detach 共享 WebView（含截图过渡），否则新页面的
    // HotwireView.attachWebView 会因 WebView 已有 parent 而拒绝 attach，
    // 导致 visit 不会执行、页面空白
    currentDestination.onBeforeNavigation()
    currentDestination.prepareNavigation {
      navController.navigate(destination.id, arguments, navOptions)
    }
    return true
  }

  private companion object {
    // 库 internal 常量（NavigatorArguments.kt）的值，手动导航时需要保持一致
    const val ARG_LOCATION = "location"
    const val ARG_NAVIGATOR_NAME = "navigator-name"
    const val ARG_PRESENTATION_CONTEXT = "presentation-context"
  }
}
