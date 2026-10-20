package mx.viernes.escucha

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Viernes (en Termux) avisa aquí cuando termina, para que la escucha se reanude. */
class FinReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Estado.reanudar = true
    }
}
