package com.xcprinter.app

/**
 * 服务器地址配置（对照 iOS Lib/Demo.swift）。
 * 切换 [current] 为 [local] 即可连接本地开发服务器。
 */
object Demo {
    const val remote = "https://linlishenghuo.com"
    const val local = "http://localhost:3000"

    val current: String
        get() = remote

    val isLocal: Boolean
        get() = current == local
}
