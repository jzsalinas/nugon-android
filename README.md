# Nugon SOS 🚨

**Nugon SOS** es una aplicación Android de código abierto diseñada para personas con movilidad reducida o condiciones médicas (como convulsiones) que requieren una forma ultrarrápida de pedir ayuda.

Esta aplicación permite enviar alertas de emergencia en menos de 2 segundos mediante la presión prolongada de los botones de volumen, incluso con la pantalla bloqueada y el dispositivo en reposo profundo.

## 🌟 Características

- **Activación por Hardware**: Intercepta la presión prolongada (1.5s) de los botones de Volumen (+ o -).
- **Redundancia Total**: Funciona con la pantalla apagada, el celular bloqueado y sin estar conectado al cargador.
- **Alertas Duales**:
  - **SMS**: Envía mensajes de texto con la ubicación exacta (Google Maps) a múltiples contactos.
  - **Notificación PWA (Web Push)**: Envía un payload JSON a un servidor backend para notificar a familiares mediante Web Push.
- **Confirmación Háptica**: Sistema de vibraciones para confirmar que el botón ha sido detectado y que la alerta ha sido enviada con éxito.
- **Monitor Permanente**: Servicio en primer plano diseñado para ser indestructible por el sistema de ahorro de energía.

## 🚀 Instalación y Configuración

Dado que la aplicación utiliza servicios de sistema críticos, sigue estos pasos para garantizar su fiabilidad:

1. **Permisos de Sistema**: Concede permisos de SMS, Ubicación (seleccionar "Permitir todo el tiempo") y Notificaciones.
2. **Servicio de Accesibilidad**:
   - Ve a `Ajustes > Accesibilidad`.
   - Activa **Nugon SOS**. Esto otorga prioridad a la app para leer eventos de hardware.
3. **Optimización de Batería**:
   - En la app, presiona "Configurar Batería".
   - Selecciona **"Sin restricciones"** (o "No optimizar"). 
4. **Configuración de Alerta**:
   - **Contactos**: Números de teléfono separados por coma (Ej: +595981123456).
   - **Sender ID**: Tu nombre para que tus familiares identifiquen quién envía la alerta.
   - **Mensaje**: Un texto corto (máx 21 caracteres) para el SMS.

> [!NOTE]
> **Compatibilidad**: En algunos dispositivos muy agresivos con el ahorro de energía, si la alerta no responde con la pantalla apagada, toca la pantalla una vez para despertarla y luego mantén presionado el botón de volumen.

## 🛠️ Tecnologías

- **Lenguaje**: Java (Android Nativo)
- **Detección**: `MediaSession` con `VolumeProvider` + `AccessibilityService`.
- **Ubicación**: `FusedLocationProviderClient` de alta precisión.
- **Red**: OkHttp para integración con el ecosistema PWA.

## 📜 Licencia

Este proyecto está bajo la licencia **MIT**. Siéntete libre de usarlo, modificarlo y distribuirlo para ayudar a quien lo necesite.

---
*Desarrollado para ayudar a quienes más lo necesitan.*
