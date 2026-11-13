package dev.bbkb.ime.core.distribution

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * A hint, not a guarantee (captive portals report online): lets [ManifestSource] serve its cache
 * instead of waiting out a connect timeout. Callers must still handle `IOException`.
 */
object NetworkState {

    /**
     * Requires INTERNET but not VALIDATED: VALIDATED arrives late and stays unset on networks that
     * block the platform probe. Unknown is reported as online so the app still tries.
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
            // Missing ACCESS_NETWORK_STATE on some restricted profiles: let the request decide.
            true
        } catch (unavailable: RuntimeException) {
            true
        }
    }

    /** For a large-download warning; unknown is reported as not metered. */
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
