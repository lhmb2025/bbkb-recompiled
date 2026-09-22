package dev.bbkb.ime.core.distribution

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * "Is there a network worth trying right now."
 *
 * This is a *hint*, never a guarantee: a captive portal, a dead DNS server and a dropped VPN all
 * report as online. Its job is to let [ManifestSource] answer out of its cache instead of
 * burning a 10-second connect timeout when the device plainly has no connectivity, and to let
 * the UI say "you're offline" instead of "something went wrong". Code must still handle an
 * `IOException` from a request made while this returned true.
 *
 * Requires `android.permission.ACCESS_NETWORK_STATE`, which this package's manifest addition
 * declares.
 */
object NetworkState {

    /**
     * Whether the active network is connected and claims to reach the internet.
     *
     * On API 23+ this reads [NetworkCapabilities.NET_CAPABILITY_INTERNET] on the active
     * network. `NET_CAPABILITY_VALIDATED` is deliberately *not* required: it is only set after
     * the platform's own connectivity probe succeeds, which is later than the first moment a
     * fetch would work, and it stays unset on networks that block the probe but route traffic
     * fine.
     *
     * Any failure to determine the state is reported as **online**, so a surprise never turns
     * into a silent "you are offline" that stops the app from even trying.
     */
    @JvmStatic
    fun isOnline(context: Context): Boolean {
        val manager = context.applicationContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return true
        return try {
            val network = manager.activeNetwork ?: return false
            val capabilities = manager.getNetworkCapabilities(network) ?: return false
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (denied: SecurityException) {
            // ACCESS_NETWORK_STATE missing on some restricted profile: assume online and let
            // the request itself be the source of truth.
            true
        } catch (unavailable: RuntimeException) {
            true
        }
    }

    /**
     * Whether the active network is metered, for a "this is a 40 MB download" warning. Same
     * hint-not-guarantee caveat as [isOnline]; unknown is reported as **not** metered.
     */
    @JvmStatic
    fun isMetered(context: Context): Boolean {
        val manager = context.applicationContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        return try {
            manager.isActiveNetworkMetered
        } catch (unavailable: RuntimeException) {
            false
        }
    }
}
