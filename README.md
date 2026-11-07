# NODO Beta 0.8 — Zona Wi-Fi sin internet

NODO puede crear una **zona Wi-Fi local** desde el teléfono Android servidor, sin router y sin datos de internet. Utiliza `WifiManager.startLocalOnlyHotspot` (Android 8+). Otros Android pueden unirse con el nombre y clave que muestra la aplicación.

Funciones implementadas:
- Descubrir > Crear zona Wi-Fi sin internet (requiere permiso Wi-Fi cercanos en Android 13+ o ubicación anterior).
- La reserva del hotspot vive en `NodoService` para que siga activa en segundo plano, siempre que Android mantenga el servicio.
- NODO muestra SSID, clave, IP local (cuando el sistema la publica) y puerto TCP; permite copiar datos y cerrar la zona.
- Los clientes pueden unirse desde Wi-Fi del sistema y abrir NODO; mDNS intenta descubrir NODO automáticamente. Si el descubrimiento no funciona en el hotspot, en Descubrir > Unirse mediante dirección IP pueden escribir el IP y puerto.
- Las direcciones manuales se guardan en el teléfono cliente para reintentos futuros mientras sigan siendo válidas.
- Se mantiene el permiso del primer enlace, mensajes, archivos, llamadas y chats grupales ya existentes.

## Límites y seguridad
- **No ofrece acceso a internet**, y es distinta del punto de acceso convencional para compartir datos del teléfono. El nombre/contraseña asignados son los que entrega Android; no deben inventarse.
- Algunos fabricantes bloquean `LocalOnlyHotspot` o no admiten modo concurrente con tethering habitual; se muestra el error, no se simula.
- La detección automática mDNS puede fallar si Android filtra multicast o el punto de acceso aísla los clientes; se incluye unión por IP como respaldo, pero el aislamiento entre clientes sigue dependiendo del sistema.
- Los teléfonos invitados deben aceptar permanecer en una Wi-Fi sin internet si Android lo pregunta.
- La llamada y el chat exigen que los teléfonos mantengan alcance Wi-Fi y la app/servicio activo. Android puede detener servicios por batería. No es Wi-Fi Direct y no usa Bluetooth como transporte.
- La comunicación aún no cifra los mensajes de extremo a extremo; usar datos de prueba.
- El certificado de NODO Beta debe ser **el mismo** que la versión 0.7 para permitir actualización. No se publica el keystore.

## Pruebas recomendadas
1. Instalar Beta 0.8 en 2–3 teléfonos.
2. En teléfono anfitrión: Descubrir > Crear zona Wi-Fi sin internet, autorizar permiso, confirmar nombre y clave.
3. En invitados: conectar desde Ajustes Wi-Fi a la red creada, aceptando el mensaje sin internet.
4. Abrir NODO en todos, autorizar nuevos contactos y enviar mensajes.
5. Si no aparecen: conectar manualmente por IP y puerto mostrados en servidor.
6. Bloquear pantallas y comprobar recepción, luego probar llamada de voz.
