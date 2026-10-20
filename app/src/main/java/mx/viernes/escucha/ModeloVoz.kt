package mx.viernes.escucha

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/** Modelo de voz en español de Vosk (pequeño, unos 39 MB). Se descarga una sola vez. */
object ModeloVoz {
    const val NOMBRE = "vosk-model-small-es-0.42"
    private const val URL_ZIP = "https://alphacephei.com/vosk/models/vosk-model-small-es-0.42.zip"

    fun ruta(c: Context): File = File(c.filesDir, NOMBRE)

    fun instalado(c: Context): Boolean = File(ruta(c), "am").isDirectory

    /** Devuelve null si todo salió bien, o un mensaje de error. */
    fun descargar(c: Context, progreso: (String) -> Unit): String? {
        val zip = File(c.cacheDir, "modelo.zip")
        try {
            ruta(c).deleteRecursively()
            val conexion = URL(URL_ZIP).openConnection() as HttpURLConnection
            conexion.connectTimeout = 15000
            conexion.readTimeout = 30000
            if (conexion.responseCode != 200) {
                return "El servidor respondió " + conexion.responseCode
            }
            val totalMb = conexion.contentLengthLong / 1_000_000
            conexion.inputStream.use { entrada ->
                zip.outputStream().use { salida ->
                    val buffer = ByteArray(64 * 1024)
                    var leidos = 0L
                    var ultimoMb = -1L
                    while (true) {
                        val n = entrada.read(buffer)
                        if (n < 0) break
                        salida.write(buffer, 0, n)
                        leidos += n
                        val mb = leidos / 1_000_000
                        if (mb != ultimoMb) {
                            ultimoMb = mb
                            progreso("Descargando modelo de voz: $mb de $totalMb MB")
                        }
                    }
                }
            }
            progreso("Descomprimiendo el modelo…")
            descomprimir(zip, c.filesDir)
            zip.delete()
            return if (instalado(c)) null else "El archivo descargado no tiene el formato esperado."
        } catch (e: Exception) {
            zip.delete()
            return "Falló la descarga: " + e.message
        }
    }

    private fun descomprimir(zip: File, destino: File) {
        val base = destino.canonicalPath + File.separator
        ZipInputStream(zip.inputStream().buffered()).use { z ->
            while (true) {
                val entrada = z.nextEntry ?: break
                val salida = File(destino, entrada.name)
                if (!salida.canonicalPath.startsWith(base)) {
                    throw SecurityException("Ruta inválida dentro del zip")
                }
                if (entrada.isDirectory) {
                    salida.mkdirs()
                } else {
                    salida.parentFile?.mkdirs()
                    salida.outputStream().use { z.copyTo(it) }
                }
                z.closeEntry()
            }
        }
    }
}
