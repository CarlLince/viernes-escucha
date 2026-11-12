package mx.viernes.escucha

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Servicio de accesibilidad de Viernes: lee la pantalla y hace toques cuando Termux se lo pide.
 *
 * Solo escucha en 127.0.0.1 (puerto 47631) y cada petición debe traer la clave que se guarda en Termux
 * con el botón «Vincular pantalla con Termux». Si la casilla de la app está apagada, no obedece a nadie.
 * Tocar botones de borrar, pagar o suscribirse exige confirmación explícita ("confirmo").
 */
class PantallaService : AccessibilityService() {

    companion object {
        const val PUERTO = 47631
        private const val MAX_LINEAS = 220
        private const val MAX_GESTOS = 40 // por cada 30 segundos

        @Volatile
        var conectado = false

        @Volatile
        var ultimaApp = ""

        @Volatile
        var ultimaAppHora = 0L

        private val RIESGO = Regex(
            "elimin|borrar|suprim|desinstal|vaciar|formatear|restablec|delete|remove|erase|uninstall|" +
                "factory reset|wipe|pagar|comprar|suscrib|donar|transferir|enviar dinero|" +
                "\\bbuy\\b|purchase|\\bpay\\b|subscribe|donate|send money|checkout|place order",
            RegexOption.IGNORE_CASE
        )
    }

    private var servidor: ServerSocket? = null
    private var hilo: Thread? = null
    private val marcas = java.util.ArrayDeque<Long>()

    override fun onServiceConnected() {
        super.onServiceConnected()
        conectado = true
        arrancarServidor()
    }

    override fun onAccessibilityEvent(evento: AccessibilityEvent?) {
        if (evento == null || evento.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val paquete = evento.packageName?.toString() ?: return
        val ignorar = paquete == packageName || paquete == "android" || paquete == "com.termux" ||
            paquete == "com.termux.api" || paquete == "com.google.android.gms" ||
            paquete.contains("systemui", true) || paquete.startsWith("com.miui.home") || paquete.contains("lbe.security", true) || paquete.contains("launcher", true) ||
            paquete.contains("inputmethod", true) || paquete.contains("permissioncontroller", true)
        if (!ignorar) {
            ultimaApp = paquete
            ultimaAppHora = System.currentTimeMillis()
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        conectado = false
        try {
            servidor?.close()
        } catch (e: Exception) {
            // ya estaba cerrado
        }
        servidor = null
        super.onDestroy()
    }

    // ---------------------------------------------------------------- servidor local

    private fun arrancarServidor() {
        if (hilo?.isAlive == true) return
        val nuevo = Thread({
            try {
                val ss = ServerSocket(PUERTO, 5, InetAddress.getByName("127.0.0.1"))
                servidor = ss
                while (!ss.isClosed) {
                    val cliente = ss.accept()
                    atender(cliente)
                }
            } catch (e: Exception) {
                // el servicio se apagó o el puerto está ocupado
            }
        }, "viernes-pantalla")
        nuevo.isDaemon = true
        nuevo.start()
        hilo = nuevo
    }

    private fun atender(s: Socket) {
        try {
            s.soTimeout = 5000
            val linea = s.getInputStream().bufferedReader(Charsets.UTF_8).readLine() ?: return
            val respuesta = try {
                procesar(JSONObject(linea))
            } catch (e: Exception) {
                fallo("Petición inválida: " + e.message)
            }
            val salida = s.getOutputStream()
            salida.write((respuesta.toString() + "\n").toByteArray(Charsets.UTF_8))
            salida.flush()
        } catch (e: Exception) {
            // el cliente se fue
        } finally {
            try {
                s.close()
            } catch (e: Exception) {
                // ya estaba cerrado
            }
        }
    }

    private fun procesar(p: JSONObject): JSONObject {
        val esperada = Ajustes.pantallaToken(this)
        val recibida = p.optString("t", "")
        if (!MessageDigest.isEqual(recibida.toByteArray(Charsets.UTF_8), esperada.toByteArray(Charsets.UTF_8))) {
            return fallo("Clave incorrecta. En la app toca «2. Vincular pantalla con Termux».")
        }
        if (!Ajustes.pantallaPermitida(this)) {
            return fallo("El control de pantalla está apagado en la app Viernes Escucha (casilla).")
        }
        return when (p.optString("c", "")) {
            "leer" -> leer()
            "captura" -> captura()
            "tocar" -> tocar(p)
            "tocar_texto" -> tocarTexto(p)
            "deslizar" -> deslizar(p)
        "multi" -> multi(p)
            "desplazar" -> desplazar(p)
            "escribir" -> escribir(p)
            "tecla" -> tecla(p)
            "ultima" -> ultima()
            else -> fallo("Comando desconocido")
        }
    }

    private fun respuestaOk(): JSONObject = JSONObject().put("ok", true)

    private fun fallo(mensaje: String): JSONObject = JSONObject().put("ok", false).put("error", mensaje)

    private fun pedirPermiso(que: String): JSONObject =
        JSONObject().put("ok", false).put("permiso", true).put(
            "error",
            "Parece borrar, pagar o suscribirse («" + que + "»). Pregunta al usuario y solo si dice que sí repite con --confirmo."
        )

    private fun haceCuanto(): String {
        val s = (System.currentTimeMillis() - ultimaAppHora) / 1000
        return if (s < 120) "hace " + s + " s" else "hace " + (s / 60) + " min"
    }

    /** La última app "de verdad" que el usuario tuvo al frente (sin contar Termux, el teclado ni el launcher). */
    private fun ultima(): JSONObject {
        if (ultimaApp.isEmpty()) return fallo("Aún no sé qué app estabas usando antes de Termux")
        return respuestaOk().put("app", ultimaApp).put("hace", (System.currentTimeMillis() - ultimaAppHora) / 1000)
    }

    // ---------------------------------------------------------------- leer la pantalla

    private fun leer(): JSONObject {
        val raiz = rootInActiveWindow
            ?: return fallo("No pude leer la pantalla (¿está bloqueada o la app la protege?)")
        val m = resources.displayMetrics
        val lineas = ArrayList<String>()
        val previa = if (ultimaApp.isNotEmpty()) " | ULTIMA_APP: " + ultimaApp + " (" + haceCuanto() + ")" else ""
        lineas.add("APP: " + raiz.packageName + previa + " | PANTALLA: " + m.widthPixels + "x" + m.heightPixels)
        recorrer(raiz, 0, lineas)
        if (lineas.size >= MAX_LINEAS) lineas.add("... (hay más; usa desplazar y vuelve a leer)")
        return respuestaOk().put("texto", lineas.joinToString("\n"))
    }

    private fun limpio(cs: CharSequence?): String =
        (cs?.toString() ?: "").replace('\n', ' ').trim().take(90)

    private fun rect(n: AccessibilityNodeInfo): Rect {
        val r = Rect()
        n.getBoundsInScreen(r)
        return r
    }

    private fun nombreClase(n: AccessibilityNodeInfo): String =
        n.className?.toString()?.substringAfterLast('.') ?: "View"

    private fun recorrer(n: AccessibilityNodeInfo, prof: Int, salida: MutableList<String>) {
        if (salida.size >= MAX_LINEAS || prof > 45) return
        if (n.isVisibleToUser) {
            val texto = limpio(n.text)
            val desc = limpio(n.contentDescription)
            val interactivo = n.isClickable || n.isEditable || n.isScrollable || n.isCheckable
            if (texto.isNotEmpty() || desc.isNotEmpty() || interactivo) {
                val r = rect(n)
                if (r.width() > 0 && r.height() > 0) {
                    val sb = StringBuilder()
                    sb.append('(').append(r.centerX()).append(',').append(r.centerY()).append(") ")
                    sb.append(nombreClase(n))
                    if (n.isPassword) {
                        sb.append(" [contraseña oculta]")
                    } else if (texto.isNotEmpty()) {
                        sb.append(" \"").append(texto).append('"')
                    }
                    if (desc.isNotEmpty() && desc != texto) sb.append(" [").append(desc).append(']')
                    if (n.isClickable) sb.append(" clic")
                    if (n.isEditable) sb.append(" editable")
                    if (n.isScrollable) sb.append(" desplazable")
                    if (n.isCheckable) sb.append(if (n.isChecked) " marcado" else " sin-marcar")
                    if (n.isSelected) sb.append(" seleccionado")
                    if (n.isFocused) sb.append(" enfocado")
                    val linea = sb.toString()
                    if (salida.isEmpty() || salida[salida.size - 1] != linea) salida.add(linea)
                }
            }
        }
        for (i in 0 until n.childCount) {
            val hijo = n.getChild(i) ?: continue
            recorrer(hijo, prof + 1, salida)
        }
    }

    // ---------------------------------------------------------------- seguridad de los toques

    private fun area(r: Rect): Long = r.width().toLong() * r.height()

    private fun nodoEn(x: Int, y: Int): AccessibilityNodeInfo? {
        val raiz = rootInActiveWindow ?: return null
        val mejor = arrayOfNulls<AccessibilityNodeInfo>(1)
        buscarEn(raiz, x, y, mejor, 0)
        return mejor[0]
    }

    private fun buscarEn(n: AccessibilityNodeInfo, x: Int, y: Int, mejor: Array<AccessibilityNodeInfo?>, prof: Int) {
        if (prof > 45) return
        if (n.isVisibleToUser) {
            val r = rect(n)
            if (r.contains(x, y)) {
                val actual = mejor[0]
                if (actual == null || area(r) <= area(rect(actual))) mejor[0] = n
            }
        }
        for (i in 0 until n.childCount) {
            val hijo = n.getChild(i) ?: continue
            buscarEn(hijo, x, y, mejor, prof + 1)
        }
    }

    private fun juntarTextos(n: AccessibilityNodeInfo, sb: StringBuilder, prof: Int) {
        sb.append(limpio(n.text)).append(' ').append(limpio(n.contentDescription)).append(' ')
        if (prof >= 4) return
        for (i in 0 until n.childCount) {
            val hijo = n.getChild(i) ?: continue
            juntarTextos(hijo, sb, prof + 1)
        }
    }

    /** Devuelve el texto del botón si parece borrar, pagar o suscribirse; si no, null. */
    private fun riesgoDe(nodo: AccessibilityNodeInfo): String? {
        var clicable: AccessibilityNodeInfo? = nodo
        var saltos = 0
        while (clicable != null && !clicable.isClickable && saltos < 6) {
            clicable = clicable.parent
            saltos++
        }
        val sb = StringBuilder()
        if (clicable != null && clicable.isClickable) {
            juntarTextos(clicable, sb, 0)
        } else {
            sb.append(limpio(nodo.text)).append(' ').append(limpio(nodo.contentDescription))
        }
        val texto = sb.toString().replace(Regex("\\s+"), " ").trim()
        return if (RIESGO.containsMatchIn(texto)) texto.take(60) else null
    }

    // ---------------------------------------------------------------- toques y gestos

    private fun trazo(x1: Int, y1: Int, x2: Int, y2: Int, ms: Long): GestureDescription {
        val camino = Path()
        camino.moveTo(x1.toFloat(), y1.toFloat())
        if (x1 != x2 || y1 != y2) camino.lineTo(x2.toFloat(), y2.toFloat())
        return GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(camino, 0L, ms))
            .build()
    }

    @Synchronized
    private fun limiteOk(): Boolean {
        val ahora = System.currentTimeMillis()
        while (true) {
            val primero = marcas.peekFirst() ?: break
            if (ahora - primero > 30000L) marcas.pollFirst() else break
        }
        if (marcas.size >= MAX_GESTOS) return false
        marcas.addLast(ahora)
        return true
    }

    private fun gesto(g: GestureDescription): JSONObject {
        if (!limiteOk()) return fallo("Demasiados toques seguidos. Espera unos segundos.")
        val fin = CountDownLatch(1)
        val exito = AtomicBoolean(false)
        val enviado = dispatchGesture(g, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                exito.set(true)
                fin.countDown()
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                fin.countDown()
            }
        }, null)
        if (!enviado) return fallo("Android rechazó el gesto")
        fin.await(3, TimeUnit.SECONDS)
        Thread.sleep(350)
        return if (exito.get()) respuestaOk() else fallo("El gesto no se completó")
    }

    private fun tocar(p: JSONObject): JSONObject {
        val m = resources.displayMetrics
        val x = p.optInt("x", -1)
        val y = p.optInt("y", -1)
        if (x < 0 || y < 0 || x >= m.widthPixels || y >= m.heightPixels) {
            return fallo("Coordenadas fuera de la pantalla (" + m.widthPixels + "x" + m.heightPixels + ")")
        }
        val destino = nodoEn(x, y)
        val riesgo = if (destino != null) riesgoDe(destino) else null
        if (riesgo != null && !p.optBoolean("confirmo", false)) return pedirPermiso(riesgo)
        return gesto(trazo(x, y, x, y, if (p.optBoolean("largo", false)) 800L else 60L))
    }

    private fun buscarTexto(
        n: AccessibilityNodeInfo,
        buscado: String,
        exacto: Boolean,
        salida: MutableList<AccessibilityNodeInfo>,
        prof: Int
    ) {
        if (prof > 45) return
        if (n.isVisibleToUser) {
            val t = limpio(n.text)
            val d = limpio(n.contentDescription)
            val coincide = if (exacto) {
                t.equals(buscado, true) || d.equals(buscado, true)
            } else {
                t.contains(buscado, true) || d.contains(buscado, true)
            }
            if (coincide) salida.add(n)
        }
        for (i in 0 until n.childCount) {
            val hijo = n.getChild(i) ?: continue
            buscarTexto(hijo, buscado, exacto, salida, prof + 1)
        }
    }

    private fun tocarTexto(p: JSONObject): JSONObject {
        val buscado = p.optString("texto", "").trim()
        if (buscado.isEmpty()) return fallo("Falta el texto que hay que tocar")
        val raiz = rootInActiveWindow ?: return fallo("No pude leer la pantalla")
        val candidatos = ArrayList<AccessibilityNodeInfo>()
        buscarTexto(raiz, buscado, p.optBoolean("exacto", false), candidatos, 0)
        if (candidatos.isEmpty()) {
            return fallo("No veo «" + buscado + "» en la pantalla. Usa leer para ver qué hay.")
        }
        val n = p.optInt("n", 1).coerceIn(1, candidatos.size)
        val nodo = candidatos[n - 1]
        val m = resources.displayMetrics
        val r = rect(nodo)
        val x = r.centerX().coerceIn(1, m.widthPixels - 1)
        val y = r.centerY().coerceIn(1, m.heightPixels - 1)
        val riesgo = riesgoDe(nodo)
        if (riesgo != null && !p.optBoolean("confirmo", false)) return pedirPermiso(riesgo)
        val resultado = gesto(trazo(x, y, x, y, 60L))
        if (resultado.optBoolean("ok")) {
            val nombre = limpio(nodo.text).ifEmpty { limpio(nodo.contentDescription) }
            resultado.put("tocado", nombre + " (" + x + "," + y + ")")
            resultado.put("coincidencias", candidatos.size)
        }
        return resultado
    }

    private fun deslizar(p: JSONObject): JSONObject {
        val ms = p.optLong("ms", 300L).coerceIn(50L, 15000L)
        return gesto(trazo(p.optInt("x1"), p.optInt("y1"), p.optInt("x2"), p.optInt("y2"), ms))
    }

    private fun gestoMulti(g: GestureDescription, esperaMs: Long): JSONObject {
        val fin = CountDownLatch(1)
        val exito = AtomicBoolean(false)
        val enviado = dispatchGesture(g, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                exito.set(true)
                fin.countDown()
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                fin.countDown()
            }
        }, null)
        if (!enviado) return fallo("Android rechazó el gesto")
        fin.await(esperaMs + 1000L, TimeUnit.MILLISECONDS)
        return if (exito.get()) respuestaOk() else fallo("El gesto no se completó")
    }

    private fun multi(p: JSONObject): JSONObject {
        val app = p.optString("app", "")
        if (app.isEmpty()) return fallo("Falta el nombre de la app (app)")
        val raiz = rootInActiveWindow ?: return fallo("No pude leer la pantalla")
        if (raiz.packageName?.toString() != app) {
            return fallo("La app en primer plano no es " + app)
        }
        val trazos = p.optJSONArray("trazos") ?: return fallo("Faltan los trazos")
        if (trazos.length() < 1 || trazos.length() > 4) {
            return fallo("Debe haber entre 1 y 4 trazos")
        }
        val m = resources.displayMetrics
        val b = GestureDescription.Builder()
        var finMs = 0L
        for (i in 0 until trazos.length()) {
            val t = trazos.optJSONObject(i) ?: return fallo("Trazo inválido")
            val x1 = t.optInt("x1", -1)
            val y1 = t.optInt("y1", -1)
            val x2 = t.optInt("x2", x1)
            val y2 = t.optInt("y2", y1)
            if (x1 < 0 || y1 < 0 || x2 < 0 || y2 < 0 ||
                x1 >= m.widthPixels || x2 >= m.widthPixels ||
                y1 >= m.heightPixels || y2 >= m.heightPixels) {
                return fallo("Coordenadas fuera de la pantalla")
            }
            val ini = t.optLong("ini", 0L).coerceIn(0L, 3000L)
            val ms = t.optLong("ms", 100L).coerceIn(10L, 3500L)
            val camino = android.graphics.Path()
            camino.moveTo(x1.toFloat(), y1.toFloat())
            camino.lineTo(x2.toFloat(), y2.toFloat())
            b.addStroke(GestureDescription.StrokeDescription(camino, ini, ms))
            if (ini + ms > finMs) finMs = ini + ms
        }
        return gestoMulti(b.build(), finMs)
    }

    private fun desplazar(p: JSONObject): JSONObject {
        val m = resources.displayMetrics
        val w = m.widthPixels
        val h = m.heightPixels
        val g = when (p.optString("direccion", "")) {
            "abajo" -> trazo(w / 2, h * 7 / 10, w / 2, h * 3 / 10, 350L)
            "arriba" -> trazo(w / 2, h * 3 / 10, w / 2, h * 7 / 10, 350L)
            "derecha" -> trazo(w * 8 / 10, h / 2, w * 2 / 10, h / 2, 350L)
            "izquierda" -> trazo(w * 2 / 10, h / 2, w * 8 / 10, h / 2, 350L)
            else -> return fallo("La dirección debe ser arriba, abajo, izquierda o derecha")
        }
        return gesto(g)
    }

    private fun escribir(p: JSONObject): JSONObject {
        val texto = p.optString("texto", "")
        val campo = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: return fallo("No hay un campo de texto seleccionado. Toca el campo primero.")
        val previo = if (p.optBoolean("agregar", false)) (campo.text?.toString() ?: "") else ""
        val args = Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, previo + texto)
        val exito = campo.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        return if (exito) respuestaOk() else fallo("No pude escribir en ese campo")
    }

    private fun tecla(p: JSONObject): JSONObject {
        val accion = when (p.optString("tecla", "")) {
            "atras" -> AccessibilityService.GLOBAL_ACTION_BACK
            "inicio" -> AccessibilityService.GLOBAL_ACTION_HOME
            "recientes" -> AccessibilityService.GLOBAL_ACTION_RECENTS
            "notificaciones" -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            "ajustes" -> AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
            "bloquear" -> AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN
            else -> -1
        }
        if (accion == -1) return fallo("Tecla desconocida (atras, inicio, recientes, notificaciones, ajustes, bloquear)")
        val exito = performGlobalAction(accion)
        Thread.sleep(400)
        return if (exito) respuestaOk() else fallo("Android no hizo esa acción")
    }

    // ---------------------------------------------------------------- foto de la pantalla

    private fun captura(): JSONObject {
        if (Build.VERSION.SDK_INT < 30) return fallo("La captura necesita Android 11 o más")
        val fin = CountDownLatch(1)
        val jpg = arrayOfNulls<String>(1)
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : AccessibilityService.TakeScreenshotCallback {
            override fun onSuccess(resultado: AccessibilityService.ScreenshotResult) {
                try {
                    val buffer = resultado.hardwareBuffer
                    val duro = Bitmap.wrapHardwareBuffer(buffer, resultado.colorSpace)
                    val suave = duro!!.copy(Bitmap.Config.ARGB_8888, false)!!
                    duro.recycle()
                    buffer.close()
                    val ancho = 720
                    val chica = if (suave.width > ancho) {
                        Bitmap.createScaledBitmap(suave, ancho, suave.height * ancho / suave.width, true)
                    } else {
                        suave
                    }
                    val flujo = ByteArrayOutputStream()
                    chica.compress(Bitmap.CompressFormat.JPEG, 70, flujo)
                    jpg[0] = Base64.encodeToString(flujo.toByteArray(), Base64.NO_WRAP)
                } catch (e: Exception) {
                    // se queda en null y se avisa abajo
                }
                fin.countDown()
            }

            override fun onFailure(codigo: Int) {
                fin.countDown()
            }
        })
        fin.await(6, TimeUnit.SECONDS)
        val datos = jpg[0] ?: return fallo("No pude sacar la foto (la app puede tener la pantalla protegida)")
        return respuestaOk().put("jpg", datos)
    }
}
