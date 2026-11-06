# NODO Beta 0.6 — Diseño modular (Android Java 17)

NODO es una aplicación **offline-first de mensajería por Wi-Fi local** con la identidad del diseño elegido (blanco, verde menta `#00D26A`, azul suave y tarjetas de 22 dp). Esta versión **conserva el mismo ID** `com.nodo.messenger.beta` que la beta 0.5 y aumenta `versionCode` a **60**. Para actualizar sin perder datos, hay que firmar el APK con la **misma clave privada de NODO Beta**, nunca con una firma aleatoria de GitHub.

## Pantallas

- **Descubrir:** radar estético, NSD/mDNS, equipos en la misma LAN/hotspot, solicitudes de enlace autorizadas. No inventa distancias: NSD no proporciona metros.
- **Chats:** conversaciones recientes con vista previa/hora, contadores de mensajes no leídos, favoritos, grupos; filtros *Todos / Cercanos / Favoritos* y accesos *Mensajes / Llamadas / Archivos*.
- **Conversación:** burbujas y estado entregado (ACK, no equivale a leído), emojis, stickers locales, notas de voz, PDF, imágenes, video y texto visualizables en NODO. Adjuntos: Galería, Cámara (imagen de vista previa), Archivo/PDF, Contacto NODO y Ubicación introducida por el usuario. Llamadas individuales y grupales experimentales por Wi-Fi local.
- **Llamadas:** voz LAN, contador y acceso a chat; videollamadas pendientes.
- **Mi perfil y ajustes:** editar nombre/foto/estado, visibilidad mediante anuncio mDNS, autorización de nuevas solicitudes, compartir última conexión cuando ambos clientes admiten ese dato, reconexión, segundo plano y notificaciones. Bluetooth/datos móviles aparecen deshabilitados con explicación hasta tener transporte real.

La barra inferior tiene **cuatro opciones:** Descubrir, Chats, Llamadas y Mi perfil. Archivos está dentro de Chats para evitar una quinta pestaña.

## Organización del código

```text
app/src/main/java/com/nodo/messenger/
├── MainActivity.java                # Composición y navegación Android
├── NodoService.java                 # Foreground service persistente y entrega de eventos
├── LocalNode.java                   # Sockets TCP, NSD/mDNS, enlace local y audio en vivo
├── AudioEngine.java                 # Captura/reproducción durante llamada
├── Store.java                       # Persistencia local de prueba
├── NodeViewer.java                 # Visor multimedia/documentos integrado
├── NodeAlerts.java                 # Canales Android y avisos
├── data/
│   └── ChatRepository.java         # Adaptador de conversaciones y estado de lectura
├── domain/
│   └── MessageRecord.java          # Modelo inmutable de mensaje
├── transport/
│   ├── NearbyTransport.java        # Contrato de transporte
│   ├── LanTransport.java           # Adaptador LAN disponible
│   └── PlannedTransport.java       # Capacidades futuras sin engañar al usuario
└── ui/
    ├── NodoTheme.java              # Paleta, elevación, tipografía y tarjetas
    ├── NodoNavIcon.java            # Iconos vectoriales sin fuentes de emojis
    └── NodoBottomBar.java          # Navegación de cuatro pestañas
```

## Funcionamiento comprobable

1. Instala exactamente la **misma versión** de NODO Beta en dos teléfonos conectados al **mismo Wi-Fi o hotspot**, aunque no tenga acceso a internet.
2. Autoriza el enlace en el dispositivo receptor; la reconexión es automática únicamente para usuarios conocidos.
3. Prueba enviar un único mensaje, verificar un solo mensaje entrante, y recibir con el servicio persistente de NODO activo.
4. Confirma el bloqueo de pantalla, deslizamiento de la actividad y límites de batería de cada fabricante.
5. En Chats añade favoritos, cambia filtros, comparte fotos, voz y documentos, y observa el contador de no leídos.
6. Revisa **Ajustes → Privacidad**: al ocultarte de la lista de cercanos, los contactos ya autorizados pueden seguir teniendo acceso al enlace.

**No implementado**: Wi-Fi Direct puro sin punto de acceso, Bluetooth, servidor relay para datos móviles, videollamadas WebRTC, cifrado de extremo a extremo, sincronización multidispositivo por internet. Las llamadas grupales son experimentales; no se garantiza mezcla de audio sin eco en todos los teléfonos. Los recibos distinguen *enviado/entregado*, pero aún no indican lectura remota. El estado de última conexión se comparte voluntariamente y solo se conoce para equipos compatibles que han establecido conexión.

**Privacidad:** el protocolo de prueba utiliza TCP local sin cifrado de extremo a extremo ni verificación criptográfica de identidad. No usar en redes públicas ni para datos confidenciales. Para una versión de producción, migrar mensajes a Room, implementar autenticación/cifrado auditado, controles de tamaño/validación de archivos, y pruebas de estabilidad con varias versiones Android.

## Compilación

Android Gradle Plugin 8.7.3; Java 17; Gradle 8.10.2; compileSdk y targetSdk 35; minSdk 26. Ejecuta `gradle :app:assembleRelease` en Android SDK preparado; sin variables `NODO_SIGNING_FILE`/`NODO_SIGNING_PASSWORD`, produce APK **sin firmar**. Para actualizaciones, cargar firma Beta en un entorno privado, nunca al GitHub público.

El workflow `nodo-modular.yml` compila el release no firmado en GitHub Actions y genera artefacto para firma privada. No incorpora credenciales.
