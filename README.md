# NODO Beta 0.7: actualizaciones integradas

NODO Beta permite revisar nuevas versiones desde **Ajustes → Actualizaciones** y desde avisos de Android. La comprobación necesita internet; los mensajes por LAN siguen funcionando sin internet.

## Flujo de actualizaciones
1. A partir de NODO Beta **0.7**, la aplicación consulta periódicamente `updates/beta.json` publicado en este repositorio.
2. Si `versionCode` es superior al instalado, ofrece **Descargar** y muestra notas de versión.
3. La app descarga el APK por HTTPS a su almacenamiento interno, comprueba SHA-256, ID de paquete y versión, y solicita confirmación para instalar con el instalador de Android.
4. Android exige autorización de instalación desde esta fuente cuando corresponde; no hay instalación silenciosa.
5. La firma del APK debe coincidir con la instalación anterior. La clave de firma privada NO debe aparecer en GitHub público.

## Publicar una nueva versión para todos los teléfonos
- Cambiar versionCode/versionName en `app/build.gradle`.
- Subir y probar el código.
- Configurar secretos privados `NODO_KEYSTORE_B64` y `NODO_KEYSTORE_PASSWORD` en **Settings > Secrets and variables > Actions**.
- Ejecutar Actions: **NODO Beta 0.7 - compilación y publicación de actualizaciones** > **Run workflow**, marcar **publicar=true**.
- El workflow sube el APK firmado y actualiza el manifiesto `updates/beta.json`. Los dispositivos detectan el nuevo número de versión. La publicación sin secretos se bloquea para impedir APK inválidos.

**Los secretos no están configurados por el asistente.** En ausencia de ellos puede compilarse un APK sin firmar para desarrollo, pero nunca se distribuye como actualización.

El manifiesto inicial apunta a la versión 0.6 firmada existente. Para NODO 0.7, publicar el APK firmado y actualizar el manifiesto de forma conjunta.

La comprobación periódica utiliza JobScheduler (~12 horas según planificación de Android) y notificaciones; no se puede garantizar una hora exacta por restricciones de batería o falta de internet. No se admiten actualizaciones desde versiones antiguas firmadas con certificados distintos. La Beta mantiene `com.nodo.messenger.beta`.

Funcionalidades de NODO: Wi-Fi LAN, chats, notas de voz, archivos, llamadas y grupos experimentales. Bluetooth, Wi-Fi Direct y videollamadas siguen pendientes.
