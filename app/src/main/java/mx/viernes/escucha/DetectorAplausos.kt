package mx.viernes.escucha

import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Detecta 2 (o 3) aplausos reales en audio de 16 kHz.
 *
 * Analiza sub-bloques de 8 ms (128 muestras; los bloques que recibe deben ser múltiplos de 128).
 * Un golpe solo cuenta como aplauso si cumple TODO esto:
 *  - Golpe: el volumen salta 4 veces o más sobre los ~50 ms anteriores y supera el pico mínimo.
 *  - Aislado: 96 ms antes del golpe hay silencio relativo (voz o música seguida se descartan).
 *  - Se apaga rápido: de 64 a 168 ms después casi no suena (una sílaba tiene una vocal detrás).
 *  - Sonido «plano»: mucha energía en agudos (las voces y los golpes sordos no pasan).
 *  - Ritmo: entre golpes pasan 0.2 a 0.7 s y entre ellos hay silencio relativo.
 * Cualquier otro sonido fuerte pegado rompe la serie.
 */
class DetectorAplausos(
    sensibilidad: Int = 3,
    aplausosRequeridos: Int = 2,
) {
    private companion object {
        const val SUB = 128 // muestras por sub-bloque (8 ms)
        const val MS_SUB = 8
        const val HIST = 128 // sub-bloques de historia (~1 s)
        const val EVAL = 20 // el golpe se evalúa 160 ms después de empezar
        const val GAP_MIN = 25 // 200 ms entre golpes
        const val GAP_MAX = 87 // ~700 ms entre golpes
        const val RAZON_GOLPE = 4.0

        // Un valor por nivel de sensibilidad (1 = muy estricto ... 5 = muy sensible).
        val PICO_MIN = intArrayOf(9000, 6500, 4500, 3200, 2200)
        val AISLAMIENTO = doubleArrayOf(0.12, 0.18, 0.25, 0.35, 0.50)
        val COLA_MAX = doubleArrayOf(0.15, 0.22, 0.30, 0.40, 0.55)
        val AGUDOS_MIN = doubleArrayOf(0.45, 0.35, 0.25, 0.18, 0.10)
    }

    private val nivel = sensibilidad.coerceIn(1, 5) - 1
    private val requeridos = if (aplausosRequeridos >= 3) 3 else 2
    private val picoMin = PICO_MIN[nivel]
    private val aislamiento = AISLAMIENTO[nivel]
    private val colaMax = COLA_MAX[nivel]
    private val agudosMin = AGUDOS_MIN[nivel]

    private val rms = DoubleArray(HIST)
    private var t = 0
    private var ultima = 0
    private var piso = 100.0

    // Golpe en estudio
    private var candActivo = false
    private var candT0 = 0
    private var candRms = 0.0
    private var candEn = 0.0
    private var candDif = 0.0
    private var candPre = 0.0

    // Golpes ya aceptados de la serie actual
    private val golpeT = IntArray(3)
    private val golpeRms = DoubleArray(3)
    private var nGolpes = 0

    var ultimoPico = 0
        private set

    val ruidoEstimado: Int
        get() = piso.toInt()

    fun reiniciar() {
        java.util.Arrays.fill(rms, 0.0)
        ultima = 0
        candActivo = false
        nGolpes = 0
    }

    /** Devuelve true cuando se completa la serie de aplausos pedida. */
    fun procesar(muestras: ShortArray, n: Int): Boolean {
        var pico = 0
        for (i in 0 until n) {
            val a = abs(muestras[i].toInt())
            if (a > pico) pico = a
        }
        ultimoPico = pico
        var listo = false
        var inicio = 0
        while (inicio + SUB <= n) {
            if (subBloque(muestras, inicio)) listo = true
            inicio += SUB
        }
        return listo
    }

    private fun media(desde: Int, hasta: Int): Double {
        if (hasta < desde) return 0.0
        var suma = 0.0
        for (i in desde..hasta) suma += rms[((i % HIST) + HIST) % HIST]
        return suma / (hasta - desde + 1)
    }

    private fun subBloque(m: ShortArray, inicio: Int): Boolean {
        var en = 0.0
        var dif = 0.0
        var p = 0
        var previa = ultima
        for (i in inicio until inicio + SUB) {
            val x = m[i].toInt()
            val a = abs(x)
            if (a > p) p = a
            en += x.toDouble() * x
            val d = (x - previa).toDouble()
            dif += d * d
            previa = x
        }
        ultima = previa
        val r = sqrt(en / SUB)
        t++
        rms[t % HIST] = r

        if (candActivo) {
            val dt = t - candT0
            if (dt <= 2) {
                if (r > candRms) candRms = r
                candEn += en
                candDif += dif
            }
            if (dt >= EVAL) {
                candActivo = false
                return evaluar()
            }
            return false
        }

        // El piso de ruido baja rápido y sube despacio.
        piso += (r - piso) * (if (r < piso) 0.05 else 0.0005)
        if (piso < 30.0) piso = 30.0

        val previo = max(media(t - 8, t - 2), piso)
        if (p >= picoMin && r >= RAZON_GOLPE * previo) {
            candActivo = true
            candT0 = t
            candRms = r
            candEn = en
            candDif = dif
            candPre = media(t - 12, t - 1)
        }
        return false
    }

    private fun evaluar(): Boolean {
        val t0 = candT0
        val cola = media(t0 + 8, t0 + EVAL)
        val agudos = if (candEn > 0.0) candDif / candEn else 0.0
        Estado.golpe = String.format(
            Locale.US,
            "fuerza %d, agudos %.2f (mín %.2f), cola %.2f (máx %.2f), antes %.2f (máx %.2f)",
            candRms.toInt(), agudos, agudosMin, cola / candRms, colaMax, candPre / candRms, aislamiento
        )
        if (candPre > aislamiento * candRms) return rechazar("ruido justo antes del golpe (voz o música)")
        if (cola > colaMax * candRms) return rechazar("no se apaga rápido (voz o música)")
        if (agudos < agudosMin) return rechazar("sonido grave, no es un aplauso")

        if (nGolpes > 0) {
            val separacion = t0 - golpeT[nGolpes - 1]
            var motivo = ""
            if (separacion < GAP_MIN || separacion > GAP_MAX) {
                motivo = "ritmo: " + (separacion * MS_SUB) + " ms entre golpes"
            } else {
                val entre = media(golpeT[nGolpes - 1] + EVAL + 1, t0 - 13)
                if (entre > aislamiento * min(golpeRms[nGolpes - 1], candRms)) motivo = "ruido entre los golpes"
            }
            if (motivo.isNotEmpty()) {
                Estado.rechazo = motivo
                nGolpes = 0
            }
        }
        golpeT[nGolpes] = t0
        golpeRms[nGolpes] = candRms
        nGolpes++
        if (nGolpes >= requeridos) {
            nGolpes = 0
            return true
        }
        return false
    }

    private fun rechazar(motivo: String): Boolean {
        Estado.rechazo = motivo
        nGolpes = 0
        return false
    }
}
