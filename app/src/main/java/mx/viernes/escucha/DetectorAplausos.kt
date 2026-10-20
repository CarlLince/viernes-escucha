package mx.viernes.escucha

import kotlin.math.abs
import kotlin.math.max

/**
 * Detecta dos aplausos seguidos en un flujo de audio de 16 kHz.
 *
 * Un aplauso es un golpe de volumen muy corto: sube de golpe y baja casi de inmediato.
 * Una voz o una música sostenida no cumple eso. Recibe bloques consecutivos de tamaño fijo
 * y devuelve true cuando detecta el segundo aplauso (entre 0.15 y 0.9 segundos después del primero).
 */
class DetectorAplausos(
    msPorBloque: Int,
    private val picoMinimo: Int = 4000,
    private val factorRuido: Double = 6.0,
) {
    private val minBloquesEntreAplausos = max(1, 150 / msPorBloque)
    private val maxBloquesEntreAplausos = max(2, 900 / msPorBloque)
    private val bloquesEnfriamiento = max(1, 100 / msPorBloque)

    private var bloque = 0
    private var ruido = 300.0
    private var picoAnterior = 0
    private var candidatoPico = 0
    private var candidatoBloque = -1000
    private var ultimoAplausoBloque = -1000
    private var primerAplausoBloque = -1000

    var ultimoPico = 0
        private set

    val ruidoEstimado: Int
        get() = ruido.toInt()

    fun reiniciar() {
        bloque = 0
        picoAnterior = 0
        candidatoPico = 0
        candidatoBloque = -1000
        ultimoAplausoBloque = -1000
        primerAplausoBloque = -1000
    }

    fun procesar(muestras: ShortArray, n: Int): Boolean {
        var pico = 0
        for (i in 0 until n) {
            val a = abs(muestras[i].toInt())
            if (a > pico) pico = a
        }
        ultimoPico = pico
        bloque++
        var doble = false

        // 1) Confirmar el candidato: dos bloques después el volumen debe haber caído a la mitad.
        if (candidatoBloque >= 0 && bloque == candidatoBloque + 2) {
            if (pico < candidatoPico * 0.5) {
                val separacion = candidatoBloque - primerAplausoBloque
                if (primerAplausoBloque >= 0 && separacion in minBloquesEntreAplausos..maxBloquesEntreAplausos) {
                    doble = true
                    primerAplausoBloque = -1000
                } else {
                    primerAplausoBloque = candidatoBloque
                }
                ultimoAplausoBloque = candidatoBloque
            }
            candidatoBloque = -1000
        }

        // 2) Buscar un candidato nuevo: golpe fuerte, repentino y por encima del ruido de fondo.
        if (candidatoBloque < 0 &&
            bloque - ultimoAplausoBloque > bloquesEnfriamiento &&
            pico > max(picoMinimo.toDouble(), ruido * factorRuido) &&
            picoAnterior < pico * 0.5
        ) {
            candidatoPico = pico
            candidatoBloque = bloque
        }

        // 3) El ruido de fondo se actualiza solo con bloques tranquilos.
        if (candidatoBloque < 0 && pico < ruido * 3 + 200) {
            ruido = ruido * 0.98 + pico * 0.02
        }
        picoAnterior = pico
        return doble
    }
}
