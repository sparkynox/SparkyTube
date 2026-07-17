package dev.sparkynox.sparkytube.support

import android.app.Activity
import android.util.Log
import com.inmobi.ads.AdMetaInfo
import com.inmobi.ads.InMobiAdRequestStatus
import com.inmobi.ads.InMobiInterstitial
import com.inmobi.ads.listeners.InterstitialAdEventListener
import com.inmobi.sdk.InMobiSdk
import com.inmobi.sdk.SdkInitializationListener
import org.json.JSONObject

object SupportAdManager {

    private const val TAG = "SupportAdManager"

    private const val ACCOUNT_ID = "595db65f209a417c84324df39612b6bd"
    private const val PLACEMENT_ID = 10000756318L

    private var initialized = false
    private var interstitial: InMobiInterstitial? = null

    interface ResultListener {
        fun onAdShown()
        fun onAdCompleted()
        fun onAdUnavailable(reason: String)
    }

    fun init(activity: Activity, onReady: () -> Unit = {}) {
        if (initialized) {
            onReady()
            return
        }
        try {
            InMobiSdk.init(activity, ACCOUNT_ID, JSONObject(), object : SdkInitializationListener {
                override fun onInitializationComplete(error: Error?) {
                    if (error == null) {
                        initialized = true
                        onReady()
                    } else {
                        Log.w(TAG, "InMobi init failed: ${error.message}")
                    }
                }
            })
        } catch (e: Exception) {
            Log.w(TAG, "InMobi init threw, support ads unavailable this session", e)
        }
    }

    fun showSupportAd(activity: Activity, listener: ResultListener) {
        if (!initialized) {
            listener.onAdUnavailable("Support ads aren't ready yet — try again in a moment.")
            return
        }

        val ad = InMobiInterstitial(activity, PLACEMENT_ID, object : InterstitialAdEventListener() {
            override fun onAdLoadSucceeded(ad: InMobiInterstitial, info: AdMetaInfo) {
                if (ad.isReady()) {
                    ad.show()
                } else {
                    listener.onAdUnavailable("No ad available right now — try again in a bit.")
                }
            }

            override fun onAdLoadFailed(ad: InMobiInterstitial, status: InMobiAdRequestStatus) {
                listener.onAdUnavailable("No ad available right now — try again in a bit.")
            }

            override fun onAdDisplayed(ad: InMobiInterstitial, info: AdMetaInfo) {
                listener.onAdShown()
            }

            override fun onAdDismissed(ad: InMobiInterstitial) {
                listener.onAdCompleted()
                interstitial = null
            }
        })

        interstitial = ad
        ad.load()
    }
}
