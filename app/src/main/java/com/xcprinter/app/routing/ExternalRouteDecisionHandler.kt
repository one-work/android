package com.xcprinter.app.routing

import androidx.core.net.toUri
import dev.hotwire.core.turbo.visit.VisitProposal
import dev.hotwire.navigation.activities.HotwireActivity
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

  override fun handle(proposal: VisitProposal, configuration: NavigatorConfiguration, activity: HotwireActivity): Router.Decision {
    return Router.Decision.NAVIGATE
  }
}
