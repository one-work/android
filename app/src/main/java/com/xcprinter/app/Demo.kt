package com.xcprinter.app

object Demo {
  const val remote = "https://linlishenghuo.com"
  const val local = "http://localhost:3000"

  val current: String
    get() = remote

  val isLocal: Boolean
    get() = current == local
}
