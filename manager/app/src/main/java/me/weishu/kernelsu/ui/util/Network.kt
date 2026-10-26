package me.weishu.kernelsu.ui.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

fun isNetworkAvailable(context: Context): Boolean {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    val network = cm.activeNetwork ?: return false
    val caps = cm.getNetworkCapabilities(network) ?: return false

    val hasTransport = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)

    return hasTransport &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}

/**
 * "有没有网络载体"：只查 INTERNET 能力，**不要求 VALIDATED**。
 *
 * 为什么单列一个：校园网 / 酒店 Wi-Fi / 强制门户会让 `NET_CAPABILITY_VALIDATED`
 * 长期为 false（系统认为"这根网线还没通过验证"），但实际 HTTP 往往能通（门户白名单、
 * 代理、或只是探测地址被挡）。商店这种"能试就试、失败再报错"的场景用 VALIDATED 当
 * 闸门会直接把用户挡在门外；真连不上时请求层自己会失败并给出可重试的错误。
 *
 * 需要"确定能上网"的判断（例如自动更新）仍然用 [isNetworkAvailable]。
 */
fun hasInternetTransport(context: Context): Boolean {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val network = cm.activeNetwork ?: return false
    val caps = cm.getNetworkCapabilities(network) ?: return false
    val hasTransport = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    return hasTransport && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
}
