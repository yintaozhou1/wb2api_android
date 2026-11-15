package com.wb.api.core

import java.net.Inet4Address
import java.net.NetworkInterface

/** 本机网络信息（用于展示局域网接入地址，PC 客户端需用手机 IP 而非 127.0.0.1）。 */
object Net {
    /** 取本机局域网 IPv4（优先 192.168.x / 10.x / 172.16-31.x），无则返回 null。 */
    fun lanIp(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces()
            .toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .mapNotNull { it.hostAddress }
            .firstOrNull {
                it.startsWith("192.168.") || it.startsWith("10.") ||
                    (it.startsWith("172.") && (it.split(".")[1].toIntOrNull() ?: 0) in 16..31)
            }
    }.getOrNull()
}
