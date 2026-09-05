# Seguridad y configuración pendiente

Este documento describe hallazgos; no corrige la seguridad ni reproduce valores sensibles.

| Hallazgo | Ubicación | Situación actual |
|---|---|---|
| Credencial datasource versionada | `Backend\effyFlow\src\main\resources\application.properties` y copias generadas | Usuario/password y URL están en configuración directa. Deben externalizarse. |
| Secreto JWT hardcodeado | `security\JwtTokenProvider.java` | La clave de firma está embebida en código. Debe venir de un secreto por ambiente y permitir rotación. |
| Credenciales por defecto en UI Kotlin | `login\ui\LoginScreen.kt` | El formulario tiene valores de desarrollo precargados. |
| Credenciales por defecto en React | `src\views\LoginView.tsx` | El formulario incluye valores de desarrollo. |
| Password local | `auth.sq`, `AuthLocalDataSource.kt` | `UserSession.password` se guarda en texto y se usa para login offline/reintentos. |
| Tokens SQLite | `auth.sq` | Access y refresh token se guardan en texto en `effy.db`. |
| Tokens React | `useLoginController.ts`, `ProtectedRoute.tsx`, `axiosConfig.ts` | Access token, refresh token y perfil se guardan en `localStorage`. |
| Endpoint Kotlin hardcodeado | `config\ServerConfig.kt` | IP/puerto dependen de una constante compilada. |
| URL React | `.env`, `axiosConfig.ts` | Configurable con Vite, pero el refresh usa una ruta absoluta distinta a la instancia configurada. |
| SQL logging | `application.properties` | `spring.jpa.show-sql` está habilitado en configuración versionada. |

## Logs

No se encontró evidencia versionada que permita afirmar qué log sensible fue eliminado previamente. El código actual:

- no imprime tokens directamente en el flujo principal inspeccionado;
- imprime payloads completos de pedido en Kotlin;
- imprime registros completos de catálogo cuando falla una inserción;
- imprime identificadores, folios, importes y resúmenes en pruebas;
- React registra presencia de sesión y errores;
- backend imprime información de codificación y puede mostrar SQL.

Estos logs pueden exponer datos empresariales, aunque no necesariamente secretos de autenticación.

## Recomendaciones

- Usar variables de entorno o un gestor de secretos para datasource y JWT.
- Separar configuración por perfiles y no versionar archivos locales.
- Externalizar `ServerConfig.BASE_IP` y unificar la URL de refresh React.
- Sustituir almacenamiento de password por un mecanismo offline explícito; proteger tokens con almacenamiento seguro del sistema.
- Desactivar SQL/payload logging fuera de desarrollo y aplicar redacción estructurada.
- Rotar cualquier secreto que haya estado versionado.
- Definir expiración, revocación y rotación de refresh tokens.
