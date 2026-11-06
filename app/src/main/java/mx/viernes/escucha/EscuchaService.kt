package mx.viernes.escucha

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer

/**
 * Escucha el micrófono con muy poco trabajo: solo mide el volumen para detectar dos aplausos.
 * Solo después de los aplausos enciende el reconocedor de voz durante unos segundos para oír «viernes».
 * Si se detecta, le pide a Termux que arranque Viernes y se pausa hasta que termine.
 */
class EscuchaService : Service() {

    companion object {
        const val EXTRA_MINUTOS = "minutos"
        const val ACCION_DETENER = "mx.viernes.escucha.DETENER"
        private const val CANAL = "escucha"
        private const val ID_NOTIFICACION = 1
        private const val FRECUENCIA = 16000
        private const val TAMANO_BLOQUE = 512
        private const val VENTANA_PALABRA_MS = 5000
        private const val PAUSA_MAXIMA_MS = 10 * 60 * 1000L
        private const val DESCANSO_MS = 10_000L

        /** «viernes» más palabras parecidas y muy comunes, para que el habla normal no se confunda con ella. */
        private val GRAMATICA: String = (
            listOf(
                "viernes", "vienes", "viene", "bienes", "tienes", "quieres", "fines",
                "jueves", "lunes", "martes", "sábado", "domingo",
                "que", "de", "la", "el", "en", "y", "a", "los", "se", "no", "un", "por", "con", "una",
                "su", "para", "es", "al", "lo", "como", "más", "pero", "sus", "le", "ya", "o", "este",
                "sí", "porque", "esta", "muy", "sin", "sobre", "también", "me", "hasta", "hay", "donde",
                "todo", "nos", "entonces", "bueno", "bien", "pues", "gracias", "hola", "vamos", "ahora",
                "aquí", "así", "esto", "eso", "ver", "ser", "tiene", "cuando", "siempre", "si", "te", "mi", "tu"
            ) + "[unk]"
            ).joinToString(",", "[", "]") { "\"$it\"" }
    }

    @Volatile private var detener = false
    private var hilo: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACCION_DETENER) {
            if (hilo?.isAlive == true) detener = true else terminar()
            return START_NOT_STICKY
        }
        if (hilo?.isAlive == true) return START_NOT_STICKY

        iniciarEnPrimerPlano()

        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Estado.mensaje = "Falta el permiso de micrófono."
            terminar()
            return START_NOT_STICKY
        }
        val minutos = intent?.getIntExtra(EXTRA_MINUTOS, 0) ?: 0
        val finMs = calcularFin(minutos)
        if (finMs == null) {
            val ini = Ajustes.horaInicio(this)
            val fin = Ajustes.horaFin(this)
            Estado.mensaje = "Fuera de horario ($ini:00 a $fin:00). Usa «Probar 30 minutos»."
            terminar()
            return START_NOT_STICKY
        }

        tomarWakeLock(finMs)
        detener = false
        Estado.escuchando = true
        Estado.pausaHastaMs = 0L
        Estado.reanudar = false
        Estado.mensaje = ""
        val nuevo = Thread({ bucle(finMs, minutos > 0) }, "escucha-viernes")
        hilo = nuevo
        nuevo.start()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        detener = true
        liberarWakeLock()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- primer plano y horario

    private fun iniciarEnPrimerPlano() {
        val gestor = getSystemService(NotificationManager::class.java)
        gestor.createNotificationChannel(
            NotificationChannel(CANAL, "Escucha de Viernes", NotificationManager.IMPORTANCE_LOW)
        )
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val detenerIntent = PendingIntent.getService(
            this, 0, Intent(this, EscuchaService::class.java).setAction(ACCION_DETENER), flags
        )
        val abrirIntent = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java), flags)
        val notificacion = Notification.Builder(this, CANAL)
            .setContentTitle("Viernes está escuchando")
            .setContentText("Aplaude " + (if (Ajustes.aplausos(this) == 3) "tres" else "dos") + " veces y di «viernes»")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(abrirIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Detener", detenerIntent)
            .setOngoing(true)
            .build()
        startForeground(ID_NOTIFICACION, notificacion, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
    }

    /** Hora (ms) en que debe terminar la escucha, o null si ahora estamos fuera del horario. */
    private fun calcularFin(minutos: Int): Long? {
        val ahora = System.currentTimeMillis()
        if (minutos > 0) return ahora + minutos * 60_000L
        val calendario = Calendar.getInstance()
        val hora = calendario.get(Calendar.HOUR_OF_DAY)
        val inicio = Ajustes.horaInicio(this)
        val fin = Ajustes.horaFin(this)
        if (hora < inicio || hora >= fin) return null
        calendario.set(Calendar.HOUR_OF_DAY, fin)
        calendario.set(Calendar.MINUTE, 0)
        calendario.set(Calendar.SECOND, 0)
        calendario.set(Calendar.MILLISECOND, 0)
        return calendario.timeInMillis
    }

    private fun tomarWakeLock(hastaMs: Long) {
        val gestor = getSystemService(PowerManager::class.java)
        val bloqueo = gestor.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "viernes:escucha")
        bloqueo.setReferenceCounted(false)
        val duracion = kotlin.math.max(1L, hastaMs - System.currentTimeMillis()) + 60_000L
        bloqueo.acquire(duracion)
        wakeLock = bloqueo
    }

    private fun liberarWakeLock() {
        val bloqueo = wakeLock
        if (bloqueo != null && bloqueo.isHeld) bloqueo.release()
        wakeLock = null
    }

    private fun terminar() {
        Estado.escuchando = false
        Estado.fase = "Detenido"
        Estado.pausaHastaMs = 0L
        liberarWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ---------------------------------------------------------------- audio

    private fun crearGrabador(): AudioRecord? {
        val minimo = AudioRecord.getMinBufferSize(
            FRECUENCIA, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val bytes = kotlin.math.max(minimo, TAMANO_BLOQUE * 2 * 4)
        val grabador = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            FRECUENCIA,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bytes
        )
        if (grabador.state != AudioRecord.STATE_INITIALIZED) {
            grabador.release()
            Estado.mensaje = "No pude abrir el micrófono (¿otra app lo está usando?)."
            return null
        }
        return grabador
    }

    /** Viernes pide oír: esta app abre el micrófono y le manda el audio hasta que él cuelgue. */
    private fun transmitirMic(grabador: AudioRecord, bloque: ShortArray, oyente: Socket) {
        try {
            grabador.startRecording()
            while (!detener && !Estado.reanudar && MicServidor.cliente === oyente) {
                if (!leerBloque(grabador, bloque)) break
                if (!MicServidor.enviar(bloque)) break
            }
        } catch (e: Exception) {
            // el micrófono se cerró
        } finally {
            try {
                grabador.stop()
            } catch (e: Exception) {
                // ya estaba detenido
            }
            MicServidor.soltar(oyente)
        }
    }

    private fun leerBloque(grabador: AudioRecord, destino: ShortArray): Boolean {
        var total = 0
        while (total < destino.size) {
            val leidos = grabador.read(destino, total, destino.size - total)
            if (leidos < 0) return false
            total += leidos
        }
        return true
    }

    // ---------------------------------------------------------------- reconocimiento de la palabra

    private fun cargarModelo(): Model? {
        if (!ModeloVoz.instalado(this)) {
            Estado.mensaje = "Sin modelo de voz: modo solo aplausos. Descárgalo en la app."
            return null
        }
        return try {
            LibVosk.setLogLevel(LogLevel.WARNINGS)
            Model(ModeloVoz.ruta(this).absolutePath)
        } catch (e: Throwable) {
            Estado.mensaje = "No pude cargar el modelo de voz (" + e.message + "): modo solo aplausos."
            null
        }
    }

    private fun crearReconocedor(modelo: Model): Recognizer? {
        return try {
            Recognizer(modelo, FRECUENCIA.toFloat(), GRAMATICA)
        } catch (e: Throwable) {
            Estado.mensaje = "No pude iniciar el reconocedor (" + e.message + "): modo solo aplausos."
            null
        }
    }

    // ---------------------------------------------------------------- bucle principal

    private fun dentroDeHorario(): Boolean {
        val hora = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return hora >= Ajustes.horaInicio(this) && hora < Ajustes.horaFin(this)
    }

    /** 1 = dijo «viernes» y nada más; -1 = dijo otra cosa; 0 = solo ruido o sonido desconocido. */
    private fun evaluarPalabra(json: String): Int {
        val texto = try {
            JSONObject(json).optString("text", "")
        } catch (e: Exception) {
            ""
        }
        val tokens = texto.split(" ").filter { it.isNotBlank() }
        val desconocidas = tokens.count { it == "[unk]" }
        val palabras = tokens.filter { it != "[unk]" }
        return when {
            palabras.isEmpty() -> 0
            desconocidas <= 1 && palabras.size <= 2 && palabras.all { it == "viernes" } -> 1
            else -> -1
        }
    }

    private fun bucle(finMs: Long, sinHorario: Boolean) {
        val modelo = cargarModelo()
        val reconocedor = if (modelo != null) crearReconocedor(modelo) else null
        val msPorBloque = TAMANO_BLOQUE * 1000 / FRECUENCIA
        val grabador = crearGrabador()
        if (grabador == null) {
            reconocedor?.close()
            modelo?.close()
            terminar()
            return
        }
        try {
            grabador.startRecording()
            val bloque = ShortArray(TAMANO_BLOQUE)
            val detector = DetectorAplausos(Ajustes.sensibilidad(this), Ajustes.aplausos(this))
            MicServidor.arrancar(this)
            val maxBloquesPalabra = VENTANA_PALABRA_MS / msPorBloque
            var esperandoPalabra = false
            var bloquesPalabra = 0
            var desconocidos = 0
            var descansoHastaMs = 0L
            Estado.fase = "Esperando aplausos"

            // Cierra la ventana de la palabra sin activar Viernes y se toma un descanso.
            fun descansar(motivo: String) {
                esperandoPalabra = false
                Estado.rechazo = motivo
                detector.reiniciar()
                descansoHastaMs = System.currentTimeMillis() + DESCANSO_MS
                Estado.fase = "Descanso"
            }

            while (!detener && System.currentTimeMillis() < finMs) {
                if (!sinHorario && !dentroDeHorario()) {
                    Estado.mensaje = "Terminó el horario de escucha."
                    break
                }
                if (Estado.pausaHastaMs > 0L) {
                    // Viernes está en marcha: se suelta el micrófono hasta que termine.
                    grabador.stop()
                    Estado.fase = "En pausa (Viernes en marcha)"
                    while (!detener && !Estado.reanudar &&
                        System.currentTimeMillis() < Estado.pausaHastaMs &&
                        System.currentTimeMillis() < finMs
                    ) {
                        val oyente = MicServidor.cliente
                        if (oyente != null) {
                            transmitirMic(grabador, bloque, oyente)
                        } else {
                            Thread.sleep(100)
                        }
                    }
                    Estado.pausaHastaMs = 0L
                    Estado.reanudar = false
                    if (detener || System.currentTimeMillis() >= finMs) break
                    detector.reiniciar()
                    esperandoPalabra = false
                    descansoHastaMs = System.currentTimeMillis() + DESCANSO_MS
                    Estado.fase = "Descanso"
                    grabador.startRecording()
                    continue
                }

                if (!leerBloque(grabador, bloque)) {
                    if (!detener) Estado.mensaje = "Error al leer el micrófono."
                    break
                }
                MicServidor.enviar(bloque)

                if (descansoHastaMs > 0L) {
                    if (System.currentTimeMillis() < descansoHastaMs) continue
                    descansoHastaMs = 0L
                    detector.reiniciar()
                    Estado.fase = "Esperando aplausos"
                }

                val dobleAplauso = detector.procesar(bloque, TAMANO_BLOQUE)
                Estado.picoActual = detector.ultimoPico
                Estado.ruido = detector.ruidoEstimado

                if (!esperandoPalabra) {
                    if (dobleAplauso) {
                        Estado.aplausosDobles = Estado.aplausosDobles + 1
                        if (reconocedor == null) {
                            activar("aplausos")
                        } else {
                            reconocedor.reset()
                            esperandoPalabra = true
                            bloquesPalabra = 0
                            desconocidos = 0
                            Estado.fase = "Di «viernes»…"
                            pitido()
                        }
                    }
                } else if (reconocedor != null) {
                    bloquesPalabra++
                    if (reconocedor.acceptWaveForm(bloque, TAMANO_BLOQUE)) {
                        val json = reconocedor.result
                        when (evaluarPalabra(json)) {
                            1 -> {
                                esperandoPalabra = false
                                activar("palabra clave")
                            }
                            -1 -> descansar("dijo otra cosa, no «viernes»")
                            else -> {
                                if (json.contains("[unk]")) {
                                    desconocidos++
                                    if (desconocidos >= 2) descansar("habla continua, no «viernes»")
                                }
                            }
                        }
                    }
                    if (esperandoPalabra && bloquesPalabra >= maxBloquesPalabra) {
                        if (evaluarPalabra(reconocedor.finalResult) == 1) {
                            esperandoPalabra = false
                            activar("palabra clave")
                        } else {
                            descansar("no dijo «viernes» a tiempo")
                        }
                    }
                }
            }
            if (!detener && Estado.mensaje.isEmpty()) {
                Estado.mensaje = "La ventana de escucha terminó."
            }
        } catch (e: Throwable) {
            Estado.mensaje = "Error: " + e.message
        } finally {
            try {
                grabador.stop()
            } catch (e: Exception) {
                // ya estaba detenido
            }
            MicServidor.parar()
            grabador.release()
            reconocedor?.close()
            modelo?.close()
            terminar()
        }
    }

    private fun activar(motivo: String) {
        Estado.ultimaActivacion = hora() + " (" + motivo + ")"
        Estado.fase = "Lanzando Viernes…"
        pitido()
        Estado.reanudar = false
        Estado.pausaHastaMs = System.currentTimeMillis() + PAUSA_MAXIMA_MS
        val error = TermuxLauncher.lanzar(this, Ajustes.comando(this))
        if (error != null) {
            Estado.mensaje = error
            Estado.pausaHastaMs = 0L
            Estado.fase = "Esperando aplausos"
        }
    }

    private fun pitido() {
        try {
            val tono = ToneGenerator(AudioManager.STREAM_MUSIC, 70)
            tono.startTone(ToneGenerator.TONE_PROP_ACK, 150)
            Handler(Looper.getMainLooper()).postDelayed({ tono.release() }, 600)
        } catch (e: Exception) {
            // sin sonido, no pasa nada
        }
    }

    private fun hora(): String = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
}
