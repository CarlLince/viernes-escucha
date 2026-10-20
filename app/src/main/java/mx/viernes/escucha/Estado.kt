package mx.viernes.escucha

/** Estado compartido entre el servicio y la pantalla (viven en el mismo proceso). */
object Estado {
    @Volatile var escuchando = false
    @Volatile var fase = "Detenido"
    @Volatile var aplausosDobles = 0
    @Volatile var picoActual = 0
    @Volatile var ruido = 0
    @Volatile var mensaje = ""
    @Volatile var ultimaActivacion = "ninguna"
    @Volatile var descargando = false

    /** Mientras Viernes está en marcha en Termux, la escucha se pausa hasta esta hora (ms). */
    @Volatile var pausaHastaMs = 0L

    /** Lo activa FinReceiver cuando Viernes termina. */
    @Volatile var reanudar = false
}
