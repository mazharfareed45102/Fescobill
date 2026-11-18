package com.fescobill.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import okhttp3.Dns
import java.net.InetAddress

/**
 * Every internet connection the phone has right now. While on Wi-Fi, mobile data is brought up as
 * well (only while the bill screen is open), so the bill can be fetched over whichever provider
 * can reach the FESCO server.
 */
class NetworkRoutes(context: Context) {

    private val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private var callback: ConnectivityManager.NetworkCallback? = null

    @Volatile
    private var cellular: Network? = null

    /** Asks Android to keep mobile data available next to Wi-Fi. */
    fun start() {
        if (callback != null) return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                cellular = network
            }

            override fun onLost(network: Network) {
                if (cellular == network) cellular = null
            }
        }
        try {
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                .build()
            cm.requestNetwork(request, cb)
            callback = cb
        } catch (e: RuntimeException) {
            // not allowed on this phone; Wi-Fi alone (or mobile data alone) is used
        }
    }

    fun stop() {
        callback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        callback = null
        cellular = null
    }

    /** The phone's default connection first, then any other connection to the internet. */
    fun routes(): List<BillFetcher.Route> {
        val default: Network? = if (Build.VERSION.SDK_INT >= 23) cm.activeNetwork else null
        val list = arrayListOf(BillFetcher.Route(default?.let { nameOf(it) } ?: "default") {})
        val others = LinkedHashSet<Network>()
        try {
            @Suppress("DEPRECATION")
            others += cm.allNetworks
        } catch (e: RuntimeException) {
            // ignore
        }
        cellular?.let { others += it }
        for (n in others) {
            if (n == default) continue
            val caps = try {
                cm.getNetworkCapabilities(n)
            } catch (e: RuntimeException) {
                null
            } ?: continue
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
            val name = nameOf(n)
            if (list.any { it.name == name }) continue
            list += BillFetcher.Route(name) { builder ->
                builder.socketFactory(n.socketFactory)
                builder.dns(object : Dns {
                    override fun lookup(hostname: String): List<InetAddress> = n.getAllByName(hostname).toList()
                })
            }
        }
        return list
    }

    private fun nameOf(n: Network): String {
        val caps = try {
            cm.getNetworkCapabilities(n)
        } catch (e: RuntimeException) {
            null
        } ?: return "network"
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile data"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            else -> "network"
        }
    }
}
