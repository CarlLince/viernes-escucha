package mx.viernes.escucha

import android.content.Context
import android.content.Intent

/** Pide a Termux que ejecute un comando (necesita allow-external-apps=true en Termux). */
object TermuxLauncher {

    /** Devuelve null si se envió bien, o un mensaje de error. */
    fun lanzar(contexto: Context, comando: String): String? {
        return try {
            val intent = Intent("com.termux.RUN_COMMAND")
            intent.setClassName("com.termux", "com.termux.app.RunCommandService")
            intent.putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash")
            intent.putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-c", comando))
            intent.putExtra("com.termux.RUN_COMMAND_WORKDIR", "/data/data/com.termux/files/home")
            intent.putExtra("com.termux.RUN_COMMAND_BACKGROUND", false)
            contexto.startForegroundService(intent)
            null
        } catch (e: SecurityException) {
            "Termux rechazó el comando: da el permiso «Ejecutar comandos en Termux» y pon allow-external-apps=true."
        } catch (e: Exception) {
            "No pude lanzar Termux: " + e.message
        }
    }
}
