package com.ztc1997.fingerprint2sleep.xposed.hook

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.fingerprint.FingerprintManager
import android.os.CancellationSignal
import com.eightbitlab.rxbus.Bus
import com.ztc1997.fingerprint2sleep.BuildConfig
import com.ztc1997.fingerprint2sleep.activity.SettingsActivity
import com.ztc1997.fingerprint2sleep.extra.StartScanningEvent
import com.ztc1997.fingerprint2sleep.quickactions.IQuickActions
import com.ztc1997.fingerprint2sleep.quickactions.XposedQuickActions
import com.ztc1997.fingerprint2sleep.xposed.FPQAModule
import com.ztc1997.fingerprint2sleep.xposed.extention.KXposedBridge
import com.ztc1997.fingerprint2sleep.xposed.extention.KXposedHelpers
import com.ztc1997.fingerprint2sleep.xposed.extention.tryAndPrintStackTrace
import de.robv.android.xposed.XposedHelpers
import me.dozen.dpreference.DPreference
import org.jetbrains.anko.fingerprintManager
import java.util.concurrent.TimeUnit

object FingerprintServiceHooks : IHooks {
    val ACTION_START_SCANNING = FingerprintServiceHooks::class.java.name + ".ACTION_START_SCANNING"
    val ACTION_ENABLED_STATE_CHANGED = FingerprintServiceHooks::class.java.name + ".ACTION_ENABLED_STATE_CHANGED"

    object MyAuthenticationCallback : FingerprintManager.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(result: FingerprintManager.AuthenticationResult?) {
            super.onAuthenticationSucceeded(result)
            FPQAModule.log("onAuthenticationSucceeded($result)")
            quickActions.performQuickAction(dPreference.getPrefString(SettingsActivity.PREF_ACTION_SINGLE_TAP,
                    SettingsActivity.VALUES_PREF_QUICK_ACTION_NONE), IQuickActions.ActionType.SingleTap)
        }

        override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
            super.onAuthenticationError(errorCode, errString)
            FPQAModule.log("onAuthenticationError($errString)")
        }

        override fun onAuthenticationFailed() {
            super.onAuthenticationFailed()
            FPQAModule.log("onAuthenticationFailed()")
            if (!dPreference.getPrefBoolean(SettingsActivity.PREF_RESPONSE_ENROLLED_FINGERPRINT_ONLY, false))
                quickActions.performQuickAction(dPreference.getPrefString(SettingsActivity.PREF_ACTION_SINGLE_TAP,
                        SettingsActivity.VALUES_PREF_QUICK_ACTION_NONE), IQuickActions.ActionType.SingleTap)
        }

        override fun onAuthenticationHelp(helpCode: Int, helpString: CharSequence?) {
            super.onAuthenticationHelp(helpCode, helpString)
            FPQAModule.log("onAuthenticationHelp($helpCode, $helpString)")
            quickActions.performQuickAction(dPreference.getPrefString(SettingsActivity.PREF_ACTION_FAST_SWIPE,
                    SettingsActivity.VALUES_PREF_QUICK_ACTION_NONE), IQuickActions.ActionType.FastSwipe)
        }
    }

    private var CLASS_FINGERPRINT_SERVICE: Class<*>? = null
    private var cancellationSignal: CancellationSignal? = null
    private lateinit var context: Context
    private lateinit var quickActions: IQuickActions
    private lateinit var dPreference: DPreference

    private var forceAccessOnce = false

    override fun doHook(loader: ClassLoader) {
        CLASS_FINGERPRINT_SERVICE = XposedHelpers.findClass(
                "com.android.server.fingerprint.FingerprintService", loader)

        KXposedBridge.hookAllConstructors(CLASS_FINGERPRINT_SERVICE!!) {
            afterHookedMethod {
                val fingerprintService = it.thisObject

                context = XposedHelpers.getObjectField(fingerprintService, "mContext") as Context

                dPreference = DPreference(context, BuildConfig.APPLICATION_ID + "_preferences")

                quickActions = XposedQuickActions(context, dPreference, loader)

                Bus.observe<StartScanningEvent>()
                        .throttleLast(100, TimeUnit.MILLISECONDS)
                        .subscribe { startScanning(context, fingerprintService) }

                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(ctx: Context, intent: Intent?) {
                        if (dPreference.getPrefBoolean(SettingsActivity.PREF_ENABLE_FINGERPRINT_QUICK_ACTION, false))
                            Bus.send(StartScanningEvent)
                        else if (!(cancellationSignal?.isCanceled ?: true))
                            cancellationSignal?.cancel()
                    }
                }

                val intentFilter = IntentFilter()
                intentFilter.addAction(ACTION_ENABLED_STATE_CHANGED)
                intentFilter.addAction(ACTION_START_SCANNING)
                intentFilter.addAction(Intent.ACTION_USER_PRESENT)

                context.registerReceiver(receiver, intentFilter)

                FPQAModule.log("CLASS_FINGERPRINT_SERVICE Constructor")
            }
        }

        tryAndPrintStackTrace {
            KXposedHelpers.findAndHookMethod(CLASS_FINGERPRINT_SERVICE!!, "canUseFingerprint",
                    String::class.java, Boolean::class.java) {
                beforeHookedMethod {
                    if (forceAccessOnce && it.args[0] == "android")
                        it.result = true
                }
            }
        }

        tryAndPrintStackTrace {
            KXposedHelpers.findAndHookMethod(CLASS_FINGERPRINT_SERVICE!!, "canUseFingerprint",
                    String::class.java) {
                beforeHookedMethod {
                    if (forceAccessOnce && it.args[0] == "android")
                        it.result = true
                }
            }
        }

        FPQAModule.log("fingerprintServiceHooks")
    }

    fun startScanning(context: Context, fingerprintService: Any) {
        FPQAModule.log("startScanning invoke")

        if (!hasClientMonitor(fingerprintService)) {
            cancellationSignal = CancellationSignal()

            forceAccessOnce = true
            context.fingerprintManager.authenticate(null, cancellationSignal, 0, MyAuthenticationCallback, null)
            forceAccessOnce = false

            FPQAModule.log("startScanning")
        }
    }

    fun hasClientMonitor(fingerprintService: Any): Boolean {
        fun hasFieldAndNonNull(obj: Any, name: String): Boolean {
            val field = XposedHelpers.findFieldIfExists(CLASS_FINGERPRINT_SERVICE, name)
            val fieldInstance = field?.get(obj)
            if (fieldInstance != null) return true
            return false
        }

        return arrayOf("mAuthClient", "mEnrollClient", "mRemoveClient", "mCurrentClient", "mPendingClient")
                .any { hasFieldAndNonNull(fingerprintService, it) }
    }
}