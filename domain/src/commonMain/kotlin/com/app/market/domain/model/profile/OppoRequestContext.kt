package com.app.market.domain.model.profile

/** Region values sent by the official OPPO store alongside the signed request headers. */
data class OppoRequestContext(
    val userRegion: String,
    val systemLocale: String,
    val supportedLocales: String,
    val locale: String,
)

val DefaultOppoRequestContext = OppoRequestContext(
    userRegion = "CN",
    systemLocale = "zh-CN",
    supportedLocales = "zh-CN",
    locale = "zh-CN;CN",
)
