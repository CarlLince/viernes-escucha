package mx.viernes.escucha

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var estadoTexto: TextView
    private lateinit var comandoCampo: EditText
    private lateinit var inicioCampo: EditText
    private lateinit var finCampo: EditText
    private val handler = Handler(Looper.getMainLooper())
    private val pad by lazy { (16 * resources.displayMetrics.density).toInt() }

    private val refrescar = object : Runnable {
        override fun run() {
            estadoTexto.text = textoEstado()
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val raiz = LinearLayout(this)
        raiz.orientation = LinearLayout.VERTICAL
        raiz.setPadding(pad, pad, pad, pad)

        agregarTexto(raiz, "Viernes Escucha", true)
        estadoTexto = agregarTexto(raiz, "")

        agregarTexto(raiz, "Paso 1: permisos y modelo de voz", true)
        agregarBoton(raiz, "Dar permisos") { pedirPermisos() }
        agregarBoton(raiz, "Descargar modelo de voz (39 MB, una sola vez)") { descargarModelo() }

        agregarTexto(raiz, "Paso 2: comprobar el enlace con Termux", true)
        agregarBoton(raiz, "Probar enlace con Termux") { probarEnlace() }
        agregarBoton(raiz, "Lanzar Viernes ahora") { lanzarViernes() }

        agregarTexto(raiz, "Ajustes", true)
        agregarTexto(raiz, "Comando que se ejecuta en Termux")
        comandoCampo = agregarCampo(raiz, "Comando", Ajustes.comando(this), InputType.TYPE_CLASS_TEXT)
        agregarTexto(raiz, "Horario de escucha (horas de 0 a 24)")
        inicioCampo = agregarCampo(raiz, "Inicio", Ajustes.horaInicio(this).toString(), InputType.TYPE_CLASS_NUMBER)
        finCampo = agregarCampo(raiz, "Fin", Ajustes.horaFin(this).toString(), InputType.TYPE_CLASS_NUMBER)
        agregarBoton(raiz, "Guardar ajustes") {
            guardar()
            aviso("Ajustes guardados")
        }

        agregarTexto(raiz, "Paso 3: escuchar", true)
        agregarBoton(raiz, "Probar 30 minutos (sin horario)") { iniciar(30) }
        agregarBoton(raiz, "Iniciar en horario") { iniciar(0) }
        agregarBoton(raiz, "Detener") { detener() }

        val scroll = ScrollView(this)
        scroll.addView(raiz)
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        handler.post(refrescar)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(refrescar)
    }

    // ---------------------------------------------------------------- acciones

    private fun pedirPermisos() {
        val lista = mutableListOf(Manifest.permission.RECORD_AUDIO, "com.termux.permission.RUN_COMMAND")
        if (Build.VERSION.SDK_INT >= 33) lista.add(Manifest.permission.POST_NOTIFICATIONS)
        requestPermissions(lista.toTypedArray(), 1)
    }

    private fun descargarModelo() {
        if (ModeloVoz.instalado(this)) {
            aviso("El modelo ya está instalado")
            return
        }
        if (Estado.descargando) {
            aviso("Ya se está descargando")
            return
        }
        val contexto = applicationContext
        Estado.descargando = true
        aviso("Descargando… mira el estado arriba")
        Thread {
            val error = ModeloVoz.descargar(contexto) { Estado.mensaje = it }
            Estado.mensaje = error ?: "Modelo de voz instalado."
            Estado.descargando = false
        }.start()
    }

    private fun probarEnlace() {
        val error = TermuxLauncher.lanzar(this, "echo 'Viernes Escucha conectado con Termux'; sleep 5")
        aviso(error ?: "Enviado. Debe abrirse Termux con un mensaje.")
    }

    private fun lanzarViernes() {
        guardar()
        val error = TermuxLauncher.lanzar(this, Ajustes.comando(this))
        aviso(error ?: "Enviado. Debe abrirse Termux con Viernes.")
    }

    private fun iniciar(minutos: Int) {
        guardar()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            aviso("Primero da el permiso de micrófono")
            pedirPermisos()
            return
        }
        val intent = Intent(this, EscuchaService::class.java)
        intent.putExtra(EscuchaService.EXTRA_MINUTOS, minutos)
        startForegroundService(intent)
    }

    private fun detener() {
        val intent = Intent(this, EscuchaService::class.java)
        intent.action = EscuchaService.ACCION_DETENER
        startService(intent)
    }

    private fun guardar() {
        val comando = comandoCampo.text.toString().trim()
        Ajustes.guardarComando(this, if (comando.isEmpty()) Ajustes.COMANDO_POR_DEFECTO else comando)
        val inicio = inicioCampo.text.toString().toIntOrNull()?.coerceIn(0, 23) ?: Ajustes.HORA_INICIO
        val fin = finCampo.text.toString().toIntOrNull()?.coerceIn(0, 24) ?: Ajustes.HORA_FIN
        Ajustes.guardarHorario(this, inicio, fin)
    }

    private fun aviso(texto: String) {
        Toast.makeText(this, texto, Toast.LENGTH_LONG).show()
    }

    // ---------------------------------------------------------------- pantalla

    private fun textoEstado(): String {
        val modeloListo = ModeloVoz.instalado(this)
        val escuchando = if (Estado.escuchando) "sí" else "no"
        val modo = if (modeloListo) "aplausos y palabra «viernes»" else "solo aplausos (falta el modelo de voz)"
        val modelo = if (modeloListo) "instalado" else "NO instalado"
        val inicio = Ajustes.horaInicio(this)
        val fin = Ajustes.horaFin(this)
        val sb = StringBuilder()
        sb.append("Escuchando: ").append(escuchando).append('\n')
        sb.append("Fase: ").append(Estado.fase).append('\n')
        sb.append("Modo: ").append(modo).append('\n')
        sb.append("Modelo de voz: ").append(modelo).append('\n')
        sb.append("Dobles aplausos detectados: ").append(Estado.aplausosDobles).append('\n')
        sb.append("Volumen: pico ").append(Estado.picoActual).append(" / ruido ").append(Estado.ruido).append('\n')
        sb.append("Última activación: ").append(Estado.ultimaActivacion).append('\n')
        sb.append("Horario: ").append(inicio).append(":00 a ").append(fin).append(":00").append('\n')
        if (Estado.mensaje.isNotEmpty()) sb.append("Mensaje: ").append(Estado.mensaje).append('\n')
        return sb.toString()
    }

    private fun agregarTexto(raiz: LinearLayout, texto: String, negrita: Boolean = false): TextView {
        val vista = TextView(this)
        vista.text = texto
        vista.textSize = if (negrita) 18f else 14f
        if (negrita) vista.setTypeface(null, Typeface.BOLD)
        vista.setPadding(0, pad / 2, 0, pad / 2)
        raiz.addView(vista)
        return vista
    }

    private fun agregarBoton(raiz: LinearLayout, texto: String, accion: () -> Unit) {
        val boton = Button(this)
        boton.text = texto
        boton.isAllCaps = false
        boton.setOnClickListener { accion() }
        raiz.addView(boton)
    }

    private fun agregarCampo(raiz: LinearLayout, pista: String, valor: String, tipo: Int): EditText {
        val campo = EditText(this)
        campo.hint = pista
        campo.setText(valor)
        campo.inputType = tipo
        campo.setSingleLine(true)
        raiz.addView(campo)
        return campo
    }
}
