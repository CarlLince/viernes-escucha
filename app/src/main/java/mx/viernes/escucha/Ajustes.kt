package mx.viernes.escucha

import android.content.Context

object Ajustes {
    const val HORA_INICIO = 7
    const val HORA_FIN = 14

    /** Se ejecuta dentro de una sesión de Termux; al terminar avisa a esta app para reanudar la escucha. */
    const val COMANDO_POR_DEFECTO =
        "source ~/.bashrc; cd ~/viernes && python viernes.py; " +
            "am broadcast -a mx.viernes.escucha.FIN -p mx.viernes.escucha > /dev/null 2>&1"

    private fun prefs(c: Context) = c.getSharedPreferences("ajustes", Context.MODE_PRIVATE)

    fun comando(c: Context): String =
        prefs(c).getString("comando", COMANDO_POR_DEFECTO) ?: COMANDO_POR_DEFECTO

    fun guardarComando(c: Context, valor: String) {
        prefs(c).edit().putString("comando", valor.trim()).apply()
    }

    fun horaInicio(c: Context): Int = prefs(c).getInt("horaInicio", HORA_INICIO)

    fun horaFin(c: Context): Int = prefs(c).getInt("horaFin", HORA_FIN)

    fun guardarHorario(c: Context, inicio: Int, fin: Int) {
        prefs(c).edit().putInt("horaInicio", inicio).putInt("horaFin", fin).apply()
    }

    /** Nivel de 1 (muy estricto) a 5 (muy sensible). */
    fun sensibilidad(c: Context): Int = prefs(c).getInt("sensibilidad", 3).coerceIn(1, 5)

    /** 2 aplausos normalmente; 3 para lugares ruidosos. */
    fun aplausos(c: Context): Int = if (prefs(c).getInt("aplausos", 2) >= 3) 3 else 2

    fun guardarAplausos(c: Context, sensibilidad: Int, aplausos: Int) {
        prefs(c).edit()
            .putInt("sensibilidad", sensibilidad.coerceIn(1, 5))
            .putInt("aplausos", if (aplausos >= 3) 3 else 2)
            .apply()
    }
}
