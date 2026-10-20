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
}
