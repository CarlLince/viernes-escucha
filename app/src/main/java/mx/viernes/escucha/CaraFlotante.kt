package mx.viernes.escucha

/*
 * CARA FLOTANTE DE VIERNES: burbuja con el orbe sobre cualquier app (incluida Termux).
 *
 * 1) AndroidManifest.xml: agregar estos permisos (antes de <application>):
 *
 *    <uses-permission android:name="android.permission.INTERNET"/>
 *    <uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW"/>
 *    <uses-permission android:name="android.permission.FOREGROUND_SERVICE"/>
 *    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE"/>
 *
 * 2) En la etiqueta <application> agregar:  android:usesCleartextTraffic="true"
 *    y dentro de <application> estos dos componentes:
 *
 *    <service android:name=".CaraFlotanteService" android:exported="false"
 *        android:foregroundServiceType="specialUse">
 *        <property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
 *            android:value="Muestra el orbe de Viernes sobre otras apps"/>
 *    </service>
 *    <activity android:name=".BurbujaActivity" android:exported="true"
 *        android:theme="@android:style/Theme.NoDisplay"
 *        android:excludeFromRecents="true" android:taskAffinity="">
 *        <intent-filter>
 *            <action android:name="android.intent.action.VIEW"/>
 *            <category android:name="android.intent.category.DEFAULT"/>
 *            <category android:name="android.intent.category.BROWSABLE"/>
 *            <data android:scheme="viernes" android:host="cara"/>
 *        </intent-filter>
 *    </activity>
 *
 * 3) Para mostrarla desde la app:
 *        startForegroundService(Intent(this, CaraFlotanteService::class.java))
 *    Desde Termux:  termux-open-url viernes://cara   (cara.iniciar(burbuja=True) ya lo hace)
 *
 * Gestos: arrastrar = mover, toque = agrandar o achicar, mantener = cerrar.
 * Se cierra sola unos segundos después de que Viernes se cierra.
 */

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.Outline
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.webkit.WebView
import android.widget.FrameLayout
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

private const val BASE = "http://127.0.0.1:8765"

class CaraFlotanteService : Service() {

    private var wm: WindowManager? = null
    private var burbuja: FrameLayout? = null
    private var web: WebView? = null
    private val ui = Handler(Looper.getMainLooper())
    private var fallos = 0

    // Revisa cada 3 s que Viernes siga vivo; espera hasta 30 s a que arranque.
    private val vigilar = object : Runnable {
        override fun run() {
            thread {
                val ok = servidorVivo()
                ui.post {
                    if (ok) {
                        fallos = 0
                        if (burbuja == null) mostrar()
                        ui.postDelayed(this, 3000L)
                    } else {
                        fallos++
                        val esperando = burbuja == null
                        if (fallos >= (if (esperando) 30 else 4)) {
                            stopSelf()
                        } else {
                            ui.postDelayed(this, if (esperando) 1000L else 3000L)
                        }
                    }
                }
            }
        }
    }

    private fun servidorVivo(): Boolean = try {
        val c = URL("$BASE/estado").openConnection() as HttpURLConnection
        c.connectTimeout = 1000
        c.readTimeout = 1000
        val ok = c.responseCode == 200
        c.disconnect()
        ok
    } catch (e: Exception) {
        false
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        primerPlano()
        if (intent?.getStringExtra("accion") == "ocultar" || !Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        fallos = 0
        ui.removeCallbacks(vigilar)
        ui.post(vigilar)
        return START_NOT_STICKY
    }

    private fun primerPlano() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val nb = if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel("cara", "Cara de Viernes", NotificationManager.IMPORTANCE_MIN)
            )
            Notification.Builder(this, "cara")
        } else {
            Notification.Builder(this)
        }
        val n = nb.setContentTitle("Cara de Viernes")
            .setContentText("Se oculta sola cuando Viernes se cierra")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(7, n, 1 shl 30) // FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            startForeground(7, n)
        }
    }

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    private fun mostrar() {
        val d = resources.displayMetrics.density
        val chico = (140 * d).toInt()
        val grande = (240 * d).toInt()
        val manager = getSystemService(WINDOW_SERVICE) as WindowManager
        wm = manager

        val lp = WindowManager.LayoutParams(
            chico,
            chico,
            if (Build.VERSION.SDK_INT >= 26) 2038 else 2002, // TYPE_APPLICATION_OVERLAY / TYPE_PHONE
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = resources.displayMetrics.widthPixels - chico - (12 * d).toInt()
        lp.y = (100 * d).toInt()

        val raiz = FrameLayout(this)
        raiz.setBackgroundColor(Color.BLACK)
        raiz.clipToOutline = true
        raiz.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setOval(0, 0, view.width, view.height)
            }
        }

        val pagina = WebView(this)
        pagina.setBackgroundColor(Color.BLACK)
        pagina.settings.javaScriptEnabled = true
        pagina.isVerticalScrollBarEnabled = false
        pagina.isHorizontalScrollBarEnabled = false
        pagina.loadUrl("$BASE/?mini=1")
        raiz.addView(pagina, FrameLayout.LayoutParams(-1, -1))

        // Capa transparente que recibe los gestos (el WebView no necesita toques).
        val capa = View(this)
        raiz.addView(capa, FrameLayout.LayoutParams(-1, -1))
        var x0 = 0
        var y0 = 0
        var tx = 0f
        var ty = 0f
        var movio = false
        var amplio = false
        capa.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    x0 = lp.x
                    y0 = lp.y
                    tx = e.rawX
                    ty = e.rawY
                    movio = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - tx
                    val dy = e.rawY - ty
                    if (Math.abs(dx) > 10 || Math.abs(dy) > 10) movio = true
                    if (movio) {
                        lp.x = x0 + dx.toInt()
                        lp.y = y0 + dy.toInt()
                        manager.updateViewLayout(raiz, lp)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (!movio) {
                        if (e.eventTime - e.downTime > 700) {
                            stopSelf()
                        } else {
                            amplio = !amplio
                            val lado = if (amplio) grande else chico
                            lp.width = lado
                            lp.height = lado
                            manager.updateViewLayout(raiz, lp)
                            raiz.post { raiz.invalidateOutline() }
                        }
                    }
                }
            }
            true
        }

        manager.addView(raiz, lp)
        burbuja = raiz
        web = pagina
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        try {
            burbuja?.let { wm?.removeView(it) }
        } catch (e: Exception) {
        }
        try {
            web?.destroy()
        } catch (e: Exception) {
        }
        burbuja = null
        web = null
        super.onDestroy()
    }
}

/** Puerta de entrada invisible: la abre  viernes://cara  (por ejemplo desde Termux). */
class BurbujaActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ocultar = intent?.data?.query == "ocultar"
        if (!ocultar && !Settings.canDrawOverlays(this)) {
            // Primera vez: lleva a la pantalla para dar el permiso «Mostrar sobre otras apps».
            val ajustes = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            ajustes.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(ajustes)
        } else {
            val i = Intent(this, CaraFlotanteService::class.java)
            if (ocultar) i.putExtra("accion", "ocultar")
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
        }
        finish()
    }
}
