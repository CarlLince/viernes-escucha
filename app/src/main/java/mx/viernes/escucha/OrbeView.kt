package mx.viernes.escucha

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import kotlin.math.min
import kotlin.math.sin

/**
 * Orbe de Viernes dibujado a mano (sin WebView). La burbuja lo usa cuando el WebView del teléfono no puede
 * mostrar la cara web. Cambia según el estado: reposo, escuchando, pensando, hablando.
 */
class OrbeView(contexto: Context) : View(contexto) {

    @Volatile
    var estado: String = "reposo"

    private val pincel = Paint(Paint.ANTI_ALIAS_FLAG)
    private val anillo = Paint(Paint.ANTI_ALIAS_FLAG)
    private val marco = RectF()
    private val inicio = System.currentTimeMillis()

    init {
        anillo.style = Paint.Style.STROKE
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val t = (System.currentTimeMillis() - inicio) / 1000f
        val cx = width / 2f
        val cy = height / 2f
        val lado = min(width, height).toFloat()
        val base = lado * 0.20f

        var velocidad = 1.0f
        var amplitud = 0.04f
        var brillo = 0.55f
        when (estado) {
            "escuchando" -> {
                velocidad = 3.0f
                amplitud = 0.10f
                brillo = 1.0f
            }
            "pensando" -> {
                velocidad = 2.0f
                amplitud = 0.06f
                brillo = 0.85f
            }
            "hablando" -> {
                velocidad = 5.5f
                amplitud = 0.14f
                brillo = 1.0f
            }
        }
        val radio = base * (1f + amplitud * sin(t * velocidad))

        // resplandor
        pincel.shader = RadialGradient(
            cx, cy, radio * 2.4f,
            intArrayOf(Color.argb((170 * brillo).toInt(), 61, 220, 151), Color.argb(0, 61, 220, 151)),
            floatArrayOf(0f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, radio * 2.4f, pincel)

        // núcleo
        pincel.shader = RadialGradient(
            cx - radio * 0.3f, cy - radio * 0.3f, radio * 1.4f,
            intArrayOf(Color.rgb(200, 255, 230), Color.rgb(46, 196, 133)),
            floatArrayOf(0f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, radio, pincel)
        pincel.shader = null

        anillo.strokeWidth = lado * 0.012f
        if (estado == "escuchando") {
            for (i in 0..1) {
                val f = (t * 0.9f + i * 0.5f) % 1f
                anillo.color = Color.argb(((1f - f) * 150).toInt(), 61, 220, 151)
                canvas.drawCircle(cx, cy, radio * (1.2f + f * 1.2f), anillo)
            }
        } else if (estado == "pensando") {
            anillo.color = Color.argb(200, 61, 220, 151)
            val r = radio * 1.5f
            marco.set(cx - r, cy - r, cx + r, cy + r)
            canvas.drawArc(marco, (t * 220f) % 360f, 110f, false, anillo)
        }

        postInvalidateDelayed(33)
    }
}
