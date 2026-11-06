package mx.viernes.escucha

import android.content.Context
import org.json.JSONObject
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest

/**
 * Micrófono en vivo para Viernes. Viernes (en Termux) se conecta a 127.0.0.1:47632, manda la clave y recibe
 * audio crudo (16 kHz, 16 bits, mono) hasta que cierra la conexión. Así el detector de voz de Viernes
 * funciona aunque la grabadora de Termux:API no sirva en ese teléfono (por ejemplo en MIUI).
 * La clave es la misma que se guarda con el botón «Vincular pantalla con Termux».
 */
object MicServidor {
    const val PUERTO = 47632

    @Volatile
    var cliente: Socket? = null

    private var servidor: ServerSocket? = null
    private var hilo: Thread? = null
    private val bytes = ByteArray(4096)

    @Synchronized
    fun arrancar(contexto: Context) {
        if (hilo?.isAlive == true) return
        val ctx = contexto.applicationContext
        val nuevo = Thread({
            try {
                val ss = ServerSocket(PUERTO, 2, InetAddress.getByName("127.0.0.1"))
                servidor = ss
                while (!ss.isClosed) {
                    atender(ctx, ss.accept())
                }
            } catch (e: Exception) {
                // se cerró o el puerto está ocupado
            }
        }, "viernes-mic")
        nuevo.isDaemon = true
        nuevo.start()
        hilo = nuevo
    }

    @Synchronized
    fun parar() {
        try {
            servidor?.close()
        } catch (e: Exception) {
            // ya estaba cerrado
        }
        servidor = null
        soltar(cliente)
    }

    private fun atender(ctx: Context, s: Socket) {
        try {
            s.soTimeout = 3000
            val linea = s.getInputStream().bufferedReader(Charsets.UTF_8).readLine() ?: ""
            val p = JSONObject(linea)
            val esperada = Ajustes.pantallaToken(ctx)
            val valida = p.optString("c", "") == "mic" &&
                MessageDigest.isEqual(p.optString("t", "").toByteArray(Charsets.UTF_8), esperada.toByteArray(Charsets.UTF_8))
            if (!valida) {
                s.close()
                return
            }
            s.soTimeout = 0
            s.tcpNoDelay = true
            val salida = s.getOutputStream()
            salida.write("VMIC".toByteArray(Charsets.US_ASCII))
            salida.flush()
            soltar(cliente)
            cliente = s
        } catch (e: Exception) {
            try {
                s.close()
            } catch (e2: Exception) {
                // ya estaba cerrado
            }
        }
    }

    fun soltar(s: Socket?) {
        if (s == null) return
        try {
            s.close()
        } catch (e: Exception) {
            // ya estaba cerrado
        }
        if (cliente === s) cliente = null
    }

    /** Manda un bloque de audio a Viernes. Devuelve false si nadie escucha o la conexión se cayó. */
    fun enviar(bloque: ShortArray): Boolean {
        val s = cliente ?: return false
        return try {
            val n = bloque.size
            val b = if (bytes.size >= n * 2) bytes else ByteArray(n * 2)
            for (i in 0 until n) {
                val v = bloque[i].toInt()
                b[2 * i] = (v and 0xFF).toByte()
                b[2 * i + 1] = ((v shr 8) and 0xFF).toByte()
            }
            s.getOutputStream().write(b, 0, n * 2)
            true
        } catch (e: Exception) {
            soltar(s)
            false
        }
    }
}
