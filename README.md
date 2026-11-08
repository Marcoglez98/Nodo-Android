# NODO Beta 0.9.2 — Administración de conversaciones y grupos

- Menú ⋮ y pulsación larga para **Vaciar mensajes** o **Eliminar conversación**.
- **Salir y eliminar grupo** borra su información en el dispositivo actual, pero no la de otros miembros.
- Confirmaciones antes de borrar; los archivos recibidos no se borran físicamente.
- Al recibir un mensaje nuevo de un contacto eliminado, la conversación vuelve a aparecer.
- Se conserva el contacto autorizado y los ajustes generales, y el grupo eliminado no se reincorpora solo.
- Firma y versión deben permanecer compatibles con NODO Beta 0.9.1.

# NODO Beta 0.9.1 — Zona Wi-Fi personalizable

Se ha añadido a **Descubrir → Crear zona Wi-Fi sin internet** un formulario para elegir y guardar el **nombre (SSID)** y **contraseña (WPA2)** que deseas usar.

- El nombre admite hasta **32 bytes UTF-8**; la contraseña debe contener **8–63 caracteres ASCII imprimibles**.
- El nombre y la clave se guardan **solo en el teléfono**. La contraseña se cifra usando Android Keystore (AES-GCM) en lugar de almacenarse en texto plano.
- En **Android 16+ (API 36)**, NODO *intenta* solicitar la zona local con esa configuración utilizando `WifiManager.startLocalOnlyHotspotWithConfiguration`. Es posible que algunos fabricantes no permitan esa opción; se muestra un mensaje y se ofrece el método alternativo.
- En **Android 8–15**, la API pública `startLocalOnlyHotspot` asigna automáticamente SSID y contraseña; las aplicaciones normales **no pueden elegirlas**. En este caso, el botón de personalización abre **Ajustes → Punto de acceso** para introducir los mismos datos en el punto de acceso del sistema. El sistema puede requerir datos móviles para habilitarlo, según el fabricante.
- Sigue disponible **Crear zona local automática sin internet**. Esta opción solicita una red sin internet con el nombre y clave elegidos por Android. El cuadro de estado siempre muestra los **datos reales** de la red activa, no los nombres guardados.
- No se cambia el protocolo LAN existente: los clientes pueden unirse mediante descubrimiento o IP manual. No se implementa Bluetooth ni Wi-Fi Direct.

Para actualizar sobre NODO Beta 0.9, el APK debe estar firmado con el **mismo certificado**; la compilación de GitHub puede ser sin firma y debe firmarse fuera del repositorio. El archivo de claves **no debe subirse** al repositorio público.

## Prueba
1. Instala Beta 0.9.1 sobre la 0.9 y abre **Descubrir → Crear zona Wi-Fi sin internet**.
2. Escribe un nombre y contraseña, pulsa **Guardar**.
3. Prueba **Crear red con mi nombre y contraseña**. Si Android no lo admite, configura el punto de acceso mediante **Abrir ajustes**; o elige **Red automática** para una red local sin internet.
4. Conecta otros móviles y comprueba el chat/llamadas por Wi-Fi local.

**Sin pruebas físicas completas:** la compatibilidad del hotspot depende de Android y el fabricante, y un punto de acceso del sistema puede actuar distinto de la red local automática.
