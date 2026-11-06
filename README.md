# NODO Beta 0.5.0 — prueba por Wi-Fi local

Esta revisión prioriza la fiabilidad. **NODO Beta** (`com.nodo.messenger.beta`) permanece independiente de NODO original y usa la firma privada persistente de la versión 0.4.1 Beta.

## Mejoras
- Servicio de red en primer plano, bloqueo Wi-Fi mientras está activado, WakeLock durante la conexión activa y receptor para volver a arrancar si Android permite el inicio después de reiniciar.
- Recuperación de conexión mediante endpoints mDNS conocidos; pings periódicos para detectar enlaces muertos.
- Mensajes de texto con un ID único y acuse de recibo: una sola rutina de reintentos en el servicio y deduplicación del lado receptor. Compatibilidad básica con NODO 0.4 (sin acuse de recibo).
- Notas de voz con pausar, continuar, detener, **escuchar antes de enviar**, enviar o descartar.
- Contador de tiempo de llamada de voz.
- Grupos locales de contactos previamente autorizados; mensajes de grupo entre miembros que están conectados en ese momento.
- **Llamada grupal experimental hasta cuatro participantes** sobre la misma LAN con el creador como retransmisor de audio; requiere aceptación de cada miembro. El audio simultáneo, el eco y la latencia todavía deben probarse y optimizarse.

## Limitaciones expresas
- La conexión funciona en **la misma red Wi-Fi o punto de acceso compartido** sin salida a Internet. NO está implementada aún la conexión por Bluetooth, Wi-Fi Direct, ni la red en malla con saltos. Encender solo Wi-Fi o Bluetooth no implica conexión entre dispositivos.
- Android no garantiza servicios permanentes después de **Forzar detención**, restricciones intensas de batería, cierre de permisos o apagado del Wi-Fi. Mantén autorizado el servicio en segundo plano y revisa el ahorro de batería; habrá una notificación de NODO conectado.
- La deduplicación de mensajes solo queda garantizada entre versiones nuevas con identificadores y acuses. Si un teléfono usa una versión vieja, puede haber limitaciones de compatibilidad.
- Los mensajes grupales **no se almacenan para reenviarlos** a miembros desconectados; para probar un grupo, conecta primero a todos. Solo archivos y audios individuales están disponibles.
- La llamada grupal es beta y requiere prueba entre varios dispositivos. A diferencia de una infraestructura profesional, esta retransmisión básica puede generar latencia o eco cuando hablan varias personas a la vez.
- No existe cifrado de extremo a extremo; usar solo en redes privadas de confianza para pruebas. No usar para información confidencial ni comunicaciones de emergencia.

## Publicación y firma
La rama `nodo-beta` compila en GitHub Actions. Si no existen los secretos `NODO_KEYSTORE_B64` y `NODO_KEYSTORE_PASSWORD`, la compilación de GitHub produce un APK **SIN FIRMAR** que no puede instalarse directamente. Para esta versión, el APK se firma fuera de GitHub usando la **misma clave** que se empleó en NODO Beta v0.4.1 y se verifica que el certificado es idéntico. Conserva la clave fuera del repositorio público y no la compartas.
