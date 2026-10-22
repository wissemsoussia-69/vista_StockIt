package com.example.stockit.util

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.stockit.LoginActivity
import com.example.stockit.R
import com.example.stockit.SplashActivity
import com.example.stockit.controller.MainController

/**
 * StockIT — Cycle de vie global de l'app (identique iOS AppState / RootView) :
 *
 *  - Quitter l'app (écran d'accueil, autre app, verrouillage) = déconnexion immédiate.
 *    Au retour, l'employé est renvoyé vers l'écran de connexion Okta.
 *  - Retour au premier plan (session ouverte) : renvoi des webhooks n8n en attente
 *    + synchronisation silencieuse Jira Assets.
 *  - Bandeau « MODE TEST » affiché en haut de tous les écrans connectés.
 *
 * [allowExternal] doit être appelé juste avant d'ouvrir une autre app volontairement
 * (page Okta, galerie, partage…) : ce passage en arrière-plan ne ferme pas la session.
 */
object AppSession : Application.ActivityLifecycleCallbacks {

    private var started = 0
    @Volatile private var locked = false
    @Volatile private var externalAllowed = false

    private const val BANNER_TAG = "stockit_test_banner"

    @JvmStatic
    fun install(app: Application) {
        app.registerActivityLifecycleCallbacks(this)
    }

    /** L'app va ouvrir une autre application à la demande de l'utilisateur : pas de verrouillage. */
    @JvmStatic
    fun allowExternal() { externalAllowed = true }

    /**
     * Écrans hors session : démarrage, connexion et écrans du SDK Auth0 (page Okta en cours).
     * Les écrans Auth0 ne doivent jamais être fermés ni réinitialiser l'autorisation « app externe » :
     * sinon l'ouverture de la page Okta (Chrome) est prise pour une sortie de l'app et la connexion échoue.
     */
    private fun isPublicScreen(a: Activity) = a is SplashActivity || a is LoginActivity || isAuth0Screen(a)

    private fun isAuth0Screen(a: Activity) = a.javaClass.name.startsWith("com.auth0.")

    override fun onActivityStarted(activity: Activity) {
        started++
        if (started != 1) return
        // Retour au premier plan
        if (locked) {
            locked = false
            if (!isPublicScreen(activity)) {
                val i = Intent(activity, SplashActivity::class.java)
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                activity.startActivity(i)
                activity.finish()
            }
            return
        }
        externalAllowed = false
        if (MainController.getCurrentUser() != null) {
            WebhookOutbox.flush(activity.applicationContext, null)
            AssetsSync.sync(null)
        }
    }

    override fun onActivityStopped(activity: Activity) {
        started = maxOf(0, started - 1)
        if (started != 0 || activity.isChangingConfigurations) return
        if (externalAllowed) return
        // Pas de session ouverte (écran de connexion, page Okta en cours) : rien à verrouiller.
        if (isPublicScreen(activity)) return
        // Passage en arrière-plan : la session est fermée immédiatement (iOS lockOnBackground).
        if (MainController.getCurrentUser() == null) return
        Auth0Manager.get(activity.applicationContext).clearLocalCredentials()
        SessionManager.get(activity.applicationContext).clear()
        MainController.logoutLocal()
        locked = true
    }

    override fun onActivityResumed(activity: Activity) {
        if (isPublicScreen(activity)) return
        // Retour dans un écran de l'app (partage / galerie annulé sans quitter l'app) : autorisation consommée.
        externalAllowed = false
        updateTestBanner(activity)
    }

    /** Bandeau discret affiché quand le mode test est actif (iOS TestModeBanner). */
    @JvmStatic
    fun updateTestBanner(activity: Activity) {
        val root = activity.findViewById<FrameLayout>(android.R.id.content) ?: return
        val existing = root.findViewWithTag<View>(BANNER_TAG)
        val on = SharedBridge.isTestMode(activity)
        if (!on) { existing?.let { root.removeView(it) }; return }
        if (existing != null) { existing.bringToFront(); return }
        val d = activity.resources.displayMetrics.density
        val tv = TextView(activity).apply {
            tag = BANNER_TAG
            text = AppLanguage.L("🧪 MODE TEST — aucune action Jira / Slack / e-mail réelle")
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(ContextCompat.getColor(activity, R.color.vista_navy))
            setPadding((10 * d).toInt(), (4 * d).toInt(), (10 * d).toInt(), (4 * d).toInt())
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 50 * d
                setColor(ContextCompat.getColor(activity, R.color.vista_amber))
            }
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            elevation = 20 * d
        }
        val lp = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = (2 * d).toInt() }
        root.addView(tv, lp)
        ViewCompat.setOnApplyWindowInsetsListener(tv) { v, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            (v.layoutParams as FrameLayout.LayoutParams).topMargin = top + (2 * d).toInt()
            v.requestLayout()
            insets
        }
        ViewCompat.requestApplyInsets(tv)
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}
}
