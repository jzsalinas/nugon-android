# Nugon SOS 🚨

**Nugon SOS** es una aplicación Android de código abierto diseñada para personas con movilidad reducida o condiciones médicas (como convulsiones) que requieren una forma ultrarrápida de pedir ayuda.

Esta aplicación permite enviar alertas de emergencia en menos de 2 segundos mediante la presión prolongada de los botones de volumen, incluso con la pantalla bloqueada.

## 🌟 Características

- **Activación por Hardware**: Intercepta la presión prolongada (1.5s) de los botones de Volumen (+ o -).
- **Alertas Redundantes**:
  - **SMS**: Envía mensajes de texto con la ubicación exacta (Google Maps) a múltiples contactos.
  - **Notificación PWA (Web Push)**: Envía una alerta a una PWA instalada por los familiares.
- **Confirmación Háptica**: Vibra al detectar la emergencia para confirmar el envío de forma ciega.
- **Funcionamiento en Bloqueo**: Diseñada para funcionar con el celular bloqueado o en modo reposo (Doze mode).
- **Privacidad**: 100% local, solo envía datos a los contactos y al servidor configurado por el usuario.

## 🚀 Instalación y Configuración

Dado que la aplicación utiliza permisos sensibles para garantizar su funcionamiento, sigue estos pasos:

1. **Permisos de Sistema**: Concede permisos de SMS, Ubicación y Notificaciones cuando la app lo solicite.
2. **Servicio de Accesibilidad**:
   - Ve a `Ajustes > Accesibilidad`.
   - Busca y activa **Nugon SOS**. Esto es necesario para detectar los botones físicos.
3. **Optimización de Batería**:
   - En la app, presiona "Configurar Batería".
   - Selecciona **"Sin restricciones"** (o "No optimizar"). Esto evita que Android cierre la app en segundo plano.
4. **Configuración de Alerta**:
   - Ingresa los números de teléfono de emergencia (separados por coma).
   - Configura un "ID de Emisor" para que tus familiares sepan quién envía la alerta.
   - (Opcional) Configura la URL de tu servidor para notificaciones PWA.

## 🛠️ Tecnologías

- **Lenguaje**: Java (Android Nativo)
- **Servicios**: `AccessibilityService`, `Foreground Service` con `WakeLock`.
- **Ubicación**: `FusedLocationProviderClient` (Google Play Services).
- **Red**: OkHttp para peticiones al backend.

## 📜 Licencia

Este proyecto está bajo la licencia **MIT**. Siéntete libre de usarlo, modificarlo y distribuirlo para ayudar a quien lo necesite.

---
*Desarrollado para ayudar a quienes más lo necesitan.*
