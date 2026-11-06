# NODO para Android — prototipo 0.1.0 alfa

**NODO** es el prototipo inicial de una app de comunicación cercana, con interfaz blanco/verde menta/azul inspirada en el boceto aprobado. Incluye icono y logo propios en la misma familia visual.

> **ESTADO:** código fuente para pruebas. **No se ha compilado ni probado en dispositivos reales** en este entorno. No debe distribuirse como mensajería segura ni usarse para comunicaciones importantes.

## Implementado en el código de esta versión

- Perfil editable con nombre, frase de estado e imagen; la foto se intercambia con contactos cuando se establece la conexión.
- Detección de dispositivos NODO en la **misma red Wi‑Fi local** mediante Android NSD/mDNS.
- Redes Wi-Fi sin salida a internet y, según el equipo, puntos de acceso/hotspots locales.
- Solicitud para enlazar dispositivos; el receptor debe autorizar el primer emparejamiento.
- Reconexión automática para identidades ya autorizadas, mientras NODO está activo.
- Chats de texto con emojis del teclado Android e historial guardado localmente.
- Guardado de mensajes de texto pendientes y reenvío cuando el contacto vuelve a estar conectado.
- Adjuntar y transferir documentos, fotos u otros archivos hasta **30 MB**; vista previa de imágenes.
- Grabación, envío y reproducción de notas de voz M4A.
- Llamadas uno-a-uno de voz por la LAN mediante audio PCM 16 kHz, controles de silenciar/altavoz/finalizar. **Prototipo; estabilidad y dispositivos compatibles sin verificar**.
- Pestañas **Cercanos, Chats, Llamadas, Archivos y Ajustes**.
- Ajustes de perfil, privacidad de emparejamiento, revocación de confianza, exportación básica de chat, historial, almacenamiento, información y ayuda.
- Icono y logotipo NODO verde menta/azul; mismos tonos y tarjetas redondeadas del diseño elegido.
- Acción de GitHub para construir `app-debug.apk` en servidores de GitHub.

## NO disponible todavía

- **Videollamadas**: existe la indicación en la interfaz pero no hay motor de video; el botón explica que está pendiente.
- Conexión directa automática **Wi-Fi Direct** entre dos teléfonos que no comparten ni Wi-Fi ni hotspot.
- Comunicación por **Bluetooth**.
- Comunicación remota **por datos móviles/internet** con servidores y señalización.
- Red en malla o retransmisión entre varios saltos.
- Chats y llamadas grupales, estados, cifrado extremo a extremo, copias de seguridad cifradas, notificaciones y conexión persistente en segundo plano.

**Seguridad:** el tráfico LAN de esta alfa NO está cifrado de extremo a extremo y las identidades se basan en IDs de prueba. Aunque el emparejamiento pide autorización, esto no impide suplantación maliciosa en redes hostiles. Usar solo con personas de confianza y redes controladas.

## Generar APK desde GitHub Actions

1. Crea un **repositorio privado nuevo** llamado `NODO-Android` en tu cuenta GitHub. No subas este proyecto dentro de otra aplicación.
2. Sube **el contenido interior** de esta carpeta a la rama `main`, incluida la carpeta oculta `.github`.
3. Ve a **Actions → Compilar NODO APK → Run workflow**; un `push` a `main` también ejecuta el flujo.
4. Si la compilación es correcta, abre la ejecución y descarga el artefacto **NODO-prueba-APK**. Dentro está `app-debug.apk`.
5. Instala la app en dos teléfonos Android. En Android 8+ debe funcionar la base de la app; Android 13 a 16 requieren pruebas adicionales según fabricante.

**Firma:** GitHub Actions puede generar claves *debug* distintas en cada ejecución. En ese caso Android no permitirá instalar una compilación encima de otra. Para un ciclo de actualizaciones real se debe configurar **una firma de publicación estable y privada**, conservando la misma clave; no publicar una clave privada dentro del repositorio. La firma estable es una tarea pendiente del proyecto.

## Prueba básica de 2 teléfonos

1. Conecta los dos Android al **mismo Wi-Fi**, aunque esa red no tenga internet. Alternativamente prueba el hotspot de uno de ellos: el comportamiento depende del fabricante y de las restricciones del punto de acceso.
2. Abre NODO en ambos y configura nombre e imagen.
3. En **Cercanos**, espera a que aparezca el nombre `NODO-...` del otro teléfono; toca **Enlazar**. El otro dispositivo tiene que pulsar **Autorizar**.
4. Comprueba que aparecen los nombres reales y el estado **Conectado por Wi-Fi local**.
5. Envía textos y emojis, una nota de voz y una foto pequeña. Revisa Archivos.
6. Inicia una llamada de voz desde el chat; permite el micrófono y comprueba el sonido en ambos equipos.
7. Separa los teléfonos o apaga temporalmente el Wi-Fi y vuelve a conectarlos: revisa la reconexión automática y el reenvío de mensajes de texto pendientes.

**Limitaciones conocidas:** la búsqueda mDNS puede estar bloqueada por aislamiento entre clientes, hotspots, VPN o ajustes del router. El escaneo y las llamadas están diseñados para uso con la app activa, no como servicio persistente. Las llamadas usan audio PCM sin supresión profesional de eco ni retransmisión adaptativa; usa audífonos para reducir realimentación.

## Base técnica

- Android nativo Java, minSdk 26, compileSdk 35, targetSdk 35.
- Gradle 8.10.2, Android Gradle Plugin 8.7.3 y Java 17.
- Sin servicios de backend ni permisos de ubicación para la modalidad Wi-Fi LAN.
- Service discovery: `NsdManager` (mDNS, `_nodo._tcp.`).
- Protocolo TCP privado de prueba: cabecera JSON con longitud + datos binarios de archivo/audio. **No usar este protocolo sin una revisión de seguridad y un diseño criptográfico real**.
- Persistencia local inicial: `SharedPreferences` y archivos internos.

## Próximos hitos sugeridos

**v0.2**: pruebas reales y errores; conexión Wi-Fi Direct, descubrimiento Bluetooth, ejecución persistente controlada mediante servicio visible, notificaciones y pruebas de reconexión.

**v0.3**: videollamadas vía WebRTC o equivalente, mejor audio (Opus y cancelación de eco), backend opcional para datos móviles, y autenticación/cifrado extremo a extremo auditados antes de uso real.

**v0.4**: grupos y llamadas grupales, manejo de colas y recibos de entrega, exportaciones y firma estable.

El boceto visual entregado por el usuario está en `design/referencia-visual-nodo.png` para guiar futuras iteraciones UI.


## NODO 0.3 alfa — servicio en segundo plano
- `NodoService` (servicio en primer plano Android `connectedDevice`): mantiene el servidor TCP, NSD/mDNS y las conexiones de contactos autorizados independientemente de `MainActivity`.
- Notificación persistente con estado de red y acción **Detener**. En llamadas iniciadas/contestadas desde la interfaz, se activa además el tipo de servicio `microphone` para continuar el audio cuando la pantalla se apaga.
- Ajustes > Segundo plano: habilitar/deshabilitar servicio y acceso a los ajustes de batería.
- La reconexión local funciona con la app en ejecución y con Wi-Fi local o hotspot; no habilita Bluetooth, Wi-Fi Direct ni mensajes por datos móviles.
- Avisos de mensaje y llamada mientras el servicio sigue vivo. Deslizar la actividad de aplicaciones recientes no debería detenerlo mientras permanezca la notificación; forzar cierre o restricciones del sistema sí pueden detenerlo.
- Las solicitudes de emparejamiento de desconocidos necesitan confirmación explícita desde la pantalla y caducan.

**Seguridad:** prototipo alfa sin cifrado de extremo a extremo. El usuario debe mantener el control de su red y permisos.

**Firma Android:** el APK generado por GitHub Actions utiliza la firma de depuración del runner y puede no actualizar APKs previos. Para instalaciones actualizables a largo plazo, configurar una llave de firma de publicación persistente mediante secretos de GitHub (no se debe publicar la llave en el repositorio).
