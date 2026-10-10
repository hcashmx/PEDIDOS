# COT-MOBILE-03A — RESULTADO

**Dictamen: PARCIALMENTE PREPARADO.** Sync ya tiene descarga íntegra y validación local SHA-256, y puede emitir aserciones RSA de operación sólo para el vendedor autenticado y autorizado. No se envían Cotizaciones al ERP. La verificación de aserciones aún no existe en ERP y la consulta local ERP→Sync respondió HTTP 500; por tanto no se declara una integración de extremo a extremo.

## 1. Auditoría de contratos

- El login remoto de Sync verifica contraseña en servidor y entrega JWT de acceso/refresh. El acceso predeterminado dura 15 minutos y el refresh siete días. El access JWT identifica al usuario por email; el filtro lo vuelve a resolver en base de datos y comprueba que siga activo.
- El login offline de Kotlin provisiona identidad y credencial local a partir de un login remoto previo. La sesión local no prueba ante Sync que quien sincroniza días después sea el mismo usuario.
- El servicio ERP técnico ya se configura con `ERP_BASE_URL` y `ERP_TOKEN`, o con `ERP_USERNAME`/`ERP_PASSWORD`. `ErpPedidoClient` encapsula autenticación, login y recuperación ante 401; se reutilizó para las lecturas nuevas.
- La revisión ERP v3 contiene `version_esquema`, `clientes_lista`, `productos`, `unidades`, `tipos_precio`, `precios_lista`, `precios_volumen`, `presentaciones`, `promociones`, alcances, `impuestos_producto`, `kits_composicion` y `configuracion_comercial`.

## 2. Descarga de revisión y validación

Sync publica estos endpoints autenticados:

```http
GET /api/sync/cotizaciones/revisiones-comerciales/actual
GET /api/sync/cotizaciones/revisiones-comerciales/{id}
Authorization: Bearer <access JWT del usuario Sync>
```

Se requiere `SINCRONIZAR_DATOS` y autorización vigente para el usuario. Para “actual”, Sync consulta primero metadata ERP y después descarga el ID inmutable; contrasta ID, fecha, esquema y huella para evitar mezclar publicaciones.

El cuerpo devuelve el paquete entero: `id`, `versionEsquema`, `publicadaEn`, `referenciaComercial`, `huellaSha256` y `datosComerciales`. No pagina ni combina los catálogos mutables existentes.

El validador soporta esquema 3, exige todos los conjuntos del contrato y configuración comercial, calcula SHA-256 y lo compara en tiempo constante. Replica el canonicalizador ERP: orden lexicográfico de las propiedades de objetos, orden original de arrays y serialización Jackson de nodos numéricos decimales. Los datos descargados por ID ya pasaron por verificación SHA-256 y HMAC dentro de ERP. Sync **no** conoce ni copia la clave HMAC; su comprobación local valida la coincidencia del snapshot con la huella recibida bajo el canal autenticado.

## 3. Distribución y evidencia

Una sola respuesta contiene una revisión completa e inmutable. Kotlin deberá verificar su huella y aplicar todos sus conjuntos dentro de una única transacción local; el paquete no debe escribirse parcialmente ni combinarse con catálogos de otras revisiones. SQLDelight y Kotlin no fueron modificados.

La respuesta HTTP exitosa sólo demuestra que Sync sirvió el paquete, no que Kotlin lo guardó. Se recomienda para una fase Kotlin un ACK posterior al commit local con usuario autenticado, dispositivo, ID de revisión y huella. Sync puede persistir esa recepción idempotentemente y devolverla al renovar la captura; una respuesta perdida se reintenta con los mismos datos. No se añadió tabla ni endpoint de ACK porque Kotlin aún no puede emitirlo y un request anticipado no probaría persistencia local.

## 4. Identidad y autorización

El endpoint de aserción es:

```http
POST /api/sync/cotizaciones/aserciones-identidad
Authorization: Bearer <access JWT remoto>
Content-Type: application/json
```

Contrato que Kotlin presentaría a Sync para firmar el request futuro a ERP:

```json
{
  "uuidCotizacion": "<uuid estable>",
  "revisionComercialId": "<uuid publicado>",
  "dispositivoId": "<id opaco local>",
  "payload": {
    "uuid": "<mismo uuid>",
    "revisionComercialId": "<misma revisión>",
    "oferta": {
      "clienteId": 123,
      "agenteVentasId": 45,
      "fecha": "2026-10-10",
      "documentoSolicitado": "FACTURA",
      "observaciones": "captura offline",
      "renglones": [
        { "productoId": 456, "presentacionId": null, "cantidad": 2,
          "precioUnitario": 850, "descuentoPorcentaje": 0 }
      ]
    }
  }
}
```

`precioUnitario` es referencia observada durante captura offline; la firma sólo la vincula al body y no la autoriza. ERP debe recalcularla contra la revisión y persistir únicamente su resultado autorizado. El DTO público móvil exacto y su adaptador permanecen pendientes en ERP.

La respuesta lleva `token`, `tokenType`, usuario/agente ERP resueltos por Sync, dispositivo, UUID, revisión, `revisionHuellaSha256`, `requestSha256`, `jti`, `issuedAt` y `expiresAt`. El dispositivo no se toma como identidad ni como sustituto del JWT.

Sync toma el email del principal creado por el filtro JWT, vuelve a consultar la identidad vigente y exige usuario activo, rol comercial `AGENTE` o `VENDEDOR`, permiso ERP efectivo `cotizaciones.crear` y exactamente una relación `agentes.id_usuario`. La consulta considera permisos de rol y permisos directos de usuario, siguiendo las tablas Spatie del ERP. El agente del payload debe coincidir con ese vínculo. Se rechazan campos libres `usuarioId`/`agenteId` y discrepancias de UUID, revisión o agente.

No se utiliza la cuenta técnica ERP como actor comercial. El acceso Sync→ERP continúa autenticado separadamente por su token/credenciales de servicio.

## 5. Aserción firmada

La aserción es JWT firmado con RSA, algoritmo fijo **RS256**, cabecera `kid`, emisor predeterminado `effyflow-sync`, audiencia `erp-cotizaciones`, expiración predeterminada de 60 segundos (configurable entre 15 y 300), `iat`, `exp` y `jti` aleatorio. Se emite sólo después de volver a descargar/verificar la revisión indicada.

Claims: `sub` y `usuarioErpId`, `agenteErpId`, `dispositivoId`, `operation=CREAR_COTIZACION`, `uuidCotizacion`, `revisionComercialId`, `revisionHuellaSha256` y `requestSha256`. El hash cubre el body comercial futuro `{uuid, revisionComercialId, oferta}` canonicalizado con propiedades de objeto ordenadas y arrays conservando su orden. `dispositivoId` se firma como contexto enviado durante una sesión autenticada; Sync no tiene hoy enrolamiento o clave por dispositivo y no acredita hardware.

El contrato de configuración usa variables externas: `COTIZACION_ASSERTION_PRIVATE_KEY_BASE64` (PKCS#8 DER RSA), `COTIZACION_ASSERTION_KEY_ID`, `COTIZACION_ASSERTION_ISSUER`, `COTIZACION_ASSERTION_AUDIENCE` y `COTIZACION_ASSERTION_VALIDITY_SECONDS`. La clave privada no se almacena en Git ni se reutiliza `APP_JWT_SECRET` o la HMAC de las revisiones. No se configuró ninguna clave real.

Para generar una clave de prueba fuera del repositorio, sustituye `C:\ruta-segura` por un directorio protegido: `openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:3072 -out C:\ruta-segura\cotizaciones-sync-private.pem`; convierte sólo localmente el PKCS#8 a DER (`openssl pkcs8 -topk8 -nocrypt -in C:\ruta-segura\cotizaciones-sync-private.pem -outform DER -out C:\ruta-segura\cotizaciones-sync-private.der`) y carga sus bytes como Base64 en `COTIZACION_ASSERTION_PRIVATE_KEY_BASE64`. Exporta la pública con `openssl pkey -in C:\ruta-segura\cotizaciones-sync-private.pem -pubout -out C:\ruta-segura\cotizaciones-sync-public.pem` para configuración ERP posterior. Nunca copies la privada a ERP/Kotlin ni imprimas la variable en logs.

Cada solicitud de aserción produce un `jti` nuevo. El UUID, usuario, agente, revisión y hash se mantienen estables en un retry; renovar access JWT o aserción no cambia esa identidad de operación. Sync no mantiene una lista de `jti` usados: ERP deberá consumirlos una sola vez con persistencia atómica. No se reenvía una aserción expirada para reconciliar una operación.

## 6. Login offline y revocación

Si el access JWT expiró y el refresh remoto sigue válido, Kotlin puede renovar sesión con el mecanismo existente. Si el refresh expiró, se requiere nuevo login remoto con credenciales; el login local offline no se convierte en prueba de identidad. La captura pendiente debe conservar UUID, revisión y payload. Si el usuario fue revocado, el endpoint de aserción lo rechaza hasta que su permiso se restablezca; volver a iniciar sesión no sustituye una autorización vigente. Un cambio de sesión no autoriza a Sync a firmar esa operación como otro usuario; el agente del payload debe coincidir con el agente del principal actual.

Sync vuelve a revisar usuario, estado, permiso y asociación al emitir cada aserción. ERP deberá volver a revisar estado, permisos y agente al consumirla, ya que la revocación puede ocurrir después de la emisión. El flujo general de refresh de Pedidos no se modificó.

## 7. Contrato exacto pendiente en ERP

ERP aún debe añadir verificación de JWT asimétrico con clave pública permitida por `kid` y rechazar algoritmos distintos de RS256, emisor/audiencia incorrectos, `iat`/`exp` inválidos, `jti` repetido, usuario inactivo, permiso retirado, relación agente-usuario inválida, UUID/revisión/hash que no coincidan con el body y referencias de revisión no publicadas o no aplicables. La verificación debe ligar `sub` a `usuarioErpId`, exigir que `agenteErpId` coincida con el payload y comprobar `requestSha256` usando exactamente el canonicalizador descrito.

La idempotencia de negocio debe seguir basada en UUID + actor + revisión + hash. `jti` es sólo anti-replay de transporte y no debe formar parte de la huella de idempotencia. ERP no verifica aún estas aserciones; Sync no llama a creación ni declara autenticación de extremo a extremo.

## 8. Evidencia de integración local

Las pruebas MockRestServer comprobaron los GET autenticados, el re-login una vez ante 401, contraste de metadata, validación del SHA, rechazo de snapshot alterado/parcial, esquema desconocido, firmas y claims, expiración, permisos, revocación, agente y reintento con nuevo `jti`.

También se intentó la consulta GET-only del ERP local usando la configuración externa ya existente, sin imprimir credenciales ni modificar registros. `GET /api/cotizaciones/revisiones-comerciales/actual` respondió HTTP 500. Los logs locales disponibles no contienen la traza de esa instancia, por lo que la causa queda **sin determinar**. La prueba de integración queda como opt-in; hasta corregir/identificar ese ERP local no se confirma consumo real de una revisión.

## 9. Compatibilidad y pruebas

Pasaron **71 pruebas focalizadas**: revisiones, identidad/agente, aserciones, seguridad MVC, login/refresh, cliente ERP y contratos de captura/estado de Pedido. `mvn -q -DskipTests package` pasó. La prueba opt-in contra ERP local se ejecutó aparte y falló con HTTP 500; no forma parte de las 71 pruebas aprobadas. PED-COM-SYNC-04, login offline, cola de Pedidos, UUID/idempotencia y PED-SYNC-R1 no recibieron cambios funcionales. La suite de Sync no se ejecutó completa.

Los cambios de Kotlin y documentación que ya estaban modificados al iniciar se conservaron fuera del stage y del commit. No se modificó Kotlin ni ERP.

## 10. Pendientes y dictamen

- Identificar y corregir el HTTP 500 de la instancia ERP local; entonces repetir la prueba GET real con una revisión publicada.
- Implementar verificador y configuración de clave pública en ERP antes de cualquier POST de creación APP.
- Implementar ACK de recepción después de commit local cuando Kotlin lo soporte; el ID de dispositivo actual es contexto, no identidad criptográfica del dispositivo.
- Modificar Kotlin en la fase siguiente para descargar/aplicar paquete atómicamente, guardar ID/huella y conservarlos en la operación offline.
- Implementar el flujo de envío de Cotización sólo después de verificación ERP y reconciliación de identidad/idempotencia.

**Dictamen: PARCIALMENTE PREPARADO.** Sync dispone de los contratos y componentes criptográficos locales; el HTTP 500 de ERP, la falta de verificación de aserciones en ERP y la ausencia de confirmación Kotlin impiden afirmar que Sync esté listo para integración funcional o que la creación móvil esté terminada.
