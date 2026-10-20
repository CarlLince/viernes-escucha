# Viernes Escucha

App de Android que escucha el micrófono con muy poco trabajo, detecta **dos aplausos**
y, solo después, oye durante 5 segundos si dices **«viernes»**. Si lo oye, le pide a Termux
que arranque Viernes (`~/viernes/viernes.py`) y se pausa hasta que Viernes termine.

- Detección de aplausos: mide el volumen (sin reconocimiento de voz, casi sin gasto).
- Palabra «viernes»: [Vosk](https://alphacephei.com/vosk/) con el modelo pequeño en español, sin conexión.
  Solo se ejecuta unos segundos después de los aplausos.
- Horario: de 7:00 a 14:00 por defecto (se cambia en la app). Android no deja arrancar un servicio
  de micrófono desde segundo plano, así que cada mañana hay que tocar «Iniciar en horario».
- Sin modelo de voz instalado funciona en modo de prueba: dos aplausos lanzan Viernes directamente.

## Instalar

1. En GitHub, pestaña **Actions**: la compilación corre sola al subir cambios. Cuando termine,
   en **Releases** descarga `app-debug.apk` e instálalo (permite instalar apps desconocidas).
2. En Termux: `mkdir -p ~/.termux && echo "allow-external-apps=true" >> ~/.termux/termux.properties && termux-reload-settings`
3. En los ajustes de Android: Termux → «Mostrar sobre otras apps» activado.
4. Abre la app: **Dar permisos**, **Descargar modelo de voz**, **Probar enlace con Termux**.
5. **Probar 30 minutos** y aplaude dos veces; la pantalla muestra el volumen y los aplausos detectados.

## Límites de esta primera versión

- Viernes usa el micrófono a través de Termux:API, que Android solo permite con Termux visible:
  el celular debe estar desbloqueado y con la pantalla encendida cuando se activa.
- Con el celular bloqueado, la siguiente versión tendría que grabar la orden desde esta app.
- Los umbrales de aplauso están en `DetectorAplausos.kt` (`picoMinimo`, `factorRuido`).
