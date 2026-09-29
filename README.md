# Nugon SOS 🚨

**Nugon SOS** es una aplicación Android de código abierto diseñada para personas con movilidad reducida o condiciones médicas (como convulsiones) que requieren una forma ultrarrápida de pedir ayuda.

Esta aplicación permite solicitar ayuda mediante una pulsación prolongada de los botones físicos de volumen usando el servicio de accesibilidad de Android.

## 🌟 Características

- **Activación por Hardware**: Intercepta la presión prolongada (1.5s) de los botones de Volumen (+ o -).
- **Alertas Duales**:
  - **SMS**: Envía mensajes de texto con la ubicación exacta (Google Maps) a múltiples contactos.
  - **Notificación PWA (Web Push)**: Envía un payload JSON a un servidor backend para notificar a familiares mediante Web Push.
- **Confirmación Háptica**: Sistema de vibraciones para confirmar que el botón ha sido detectado y que la alerta ha sido enviada con éxito.
- **Vinculación segura**: Pairing temporal para asociar familiares sin cuentas ni identificadores manuales.

## 🚀 Instalación y Configuración

Dado que la aplicación utiliza servicios de sistema críticos, sigue estos pasos para garantizar su fiabilidad:

1. **Permisos de Sistema**: Concede SMS y, opcionalmente, ubicación para adjuntar coordenadas.
2. **Servicio de Accesibilidad**:
   - Ve a `Ajustes > Accesibilidad`.
   - Activa **Nugon SOS**. Esto otorga prioridad a la app para leer eventos de hardware.
3. **Configuración de Alerta**:
   - **Contactos**: Números de teléfono separados por coma (Ej: +595981123456).
   - **Mensaje**: Un texto corto (máx 21 caracteres) para el SMS.
4. **Vinculación familiar**: Genera un código temporal en Android e introdúcelo en la PWA.

El código visible se conserva localmente hasta que se utiliza o vence. Una actualización normal de
la aplicación conserva `deviceId`, `deviceSecret` y los vínculos existentes; sólo una ausencia o
corrupción real de las credenciales locales provoca que se genere una identidad nueva.

> [!NOTE]
> **Compatibilidad**: En algunos dispositivos muy agresivos con el ahorro de energía, si la alerta no responde con la pantalla apagada, toca la pantalla una vez para despertarla y luego mantén presionado el botón de volumen.

## 🛠️ Backend oficial y self-hosting

La compilación oficial utiliza:

`https://nugon.prisma.com.py/api/v1`

La URL no es editable en la aplicación. Quien mantenga una instancia propia puede definirla en
tiempo de compilación mediante una propiedad Gradle pública, siempre usando HTTPS:

```bash
./gradlew assembleRelease \
  -PNUGON_BACKEND_URL=https://nugon.example.org/api/v1
```

No coloque secretos en `NUGON_BACKEND_URL`; el valor se incorpora como `BuildConfig.NUGON_BACKEND_URL`.
Cada origen PWA mantiene sus propias Web Push subscriptions, por lo que cambiar de servidor requiere
registrar el dispositivo y repetir el pairing.

## 🛠️ Tecnologías

- **Lenguaje**: Java (Android Nativo)
- **Detección**: `AccessibilityService` con filtrado de teclas de volumen.
- **Ubicación**: `FusedLocationProviderClient` de alta precisión.
- **Red**: OkHttp para integración con el ecosistema PWA.

## 📜 Licencia

Este proyecto está bajo la licencia **MIT**. Siéntete libre de usarlo, modificarlo y distribuirlo para ayudar a quien lo necesite.

---
*Desarrollado para ayudar a quienes más lo necesitan.*
