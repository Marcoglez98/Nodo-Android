# NODO Beta v0.4.1 — instalación paralela y firma permanente

**Importante:** la 0.1, 0.2, 0.3 y 0.4 originales fueron firmadas con distintas claves efímeras del runner de GitHub. No se pueden actualizar entre sí sin la clave original. **NO desinstalar la aplicación original antes de exportar los chats importantes.**

Esta rama de NODO permite instalar **NODO Beta** junto a NODO original, sin sobrescribir sus datos:
- Application ID: `com.nodo.messenger.beta`.
- Nombre visible: `NODO Beta`.
- Versión: `0.4.1-beta` (`versionCode 41`).
- Proveedor de archivos propio con autoridad `com.nodo.messenger.beta.files`.
- Misma experiencia y funciones de NODO 0.4 alfa; contactos e historial separados de la app anterior.

## Firma permanente (necesaria para actualizaciones futuras)

No guardar keystores ni contraseñas en Git, especialmente porque este repositorio es público. La rama espera estos secretos de GitHub Actions:
1. `NODO_KEYSTORE_B64`: contenido base64 del archivo privado `nodo-beta-signing.p12`.
2. `NODO_KEYSTORE_PASSWORD`: contraseña del keystore/alias `nodo`.

Configura los secretos en **Settings → Secrets and variables → Actions**. Con ambos presentes, el workflow compila un **release firmado**. Si faltan, compila un APK **sin firmar** que no debe instalarse. Conserva una copia privada del keystore y contraseña, porque **todas las futuras actualizaciones de Beta requieren la misma clave**. La firma no permite actualizar NODO original, solo futuras versiones de NODO Beta.

Para ensayar la instalación paralela, detener el servicio en segundo plano de la aplicación NODO original antes de usar Beta; las dos versiones pueden competir por el mismo puerto de comunicación si se ejecutan juntas en el mismo dispositivo.

> Estado: prototipo para pruebas; sin cifrado de extremo a extremo. Se necesita Wi-Fi o punto de acceso común, y aún no hay videollamadas ni Bluetooth/Wi-Fi Direct.
