# Contrato HTTP del MVP

La fuente de verdad es [openapi/hila-v1.json](../openapi/hila-v1.json), OpenAPI 3.0.3, versión `1.0.0-draft.2`. Describe el contrato objetivo de **todo el MVP**. Cada operación declara `x-access` y `x-implementation-status`: el estado de cada operación se actualiza al verificar su implementación. Definir una operación aquí no la habilita en el servidor.

El contrato permite preparar formularios, tipos y estados de interfaz. La entrega funcional sigue el orden de [PRIMER_FLUJO_VERTICAL.md](PRIMER_FLUJO_VERTICAL.md). No anunciar funciones disponibles hasta que funcionen contra la API real.

## Convenciones

- Origen compartido con la web y prefijo `/api/v1`; en desarrollo Vite ya reenvía `/api`. En producción hace falta configurar un proxy equivalente; Nginx del frontend actualmente solo sirve archivos estáticos.
- JSON en `camelCase`; UUID para identificadores; instantes RFC 3339 con offset, normalizados a UTC al responder. Actividades y disponibilidad semanal se interpretan en `America/Bogota`. Horas semanales: `HH:mm`, lunes=1 y domingo=7.
- Los DTO de escritura rechazan propiedades desconocidas. El servidor normaliza espacios periféricos de textos y correo (`trim` y minúsculas); no modifica ni recorta contraseñas. Un texto obligatorio no puede quedar vacío tras normalizar. Las longitudes del esquema son límites HTTP, además de las restricciones de PostgreSQL.
- `null` elimina un valor opcional; los campos requeridos se envían incluso si admiten `null`. `PUT` sustituye todo el recurso descrito por su DTO, no asociaciones administradas por otras rutas. `PATCH` solo modifica los campos declarados en su DTO. No aceptar estados, roles, autores ni identificadores adicionales enviados por el cliente.
- `200`: lectura/actualización; `201`: recurso nuevo, con `Location` relativo bajo `/api/v1`; `202`: solicitud de correo aceptada sin prometer entrega; `204`: éxito sin cuerpo. Perfil y seguimiento usan `PUT` con `200` tanto al crear como al sustituir.
- Una lista paginada devuelve `{items, page, size, totalElements, totalPages}`. `page` comienza en 0, `size` por defecto 20, máximo 100. Sin resultados: `items: []`, `totalElements: 0`, `totalPages: 0`. Las colecciones de capacidades y feedback usan arrays acotados o naturalmente limitados, según OpenAPI.
- Orden fijo: catálogo de necesidades por `startsAt ASC, id ASC`; catálogos de capacidades por `name ASC, id ASC`; el resto por fecha de creación/registro descendente y UUID descendente. Las membresías usan `createdAt`, las revisiones `decidedAt`, las postulaciones `appliedAt` y auditoría `occurredAt`. No existe un parámetro libre `sort`.
- Los filtros se combinan con AND. `q` hace búsqueda textual sin distinguir mayúsculas en nombre/título y resumen si existe; no es sintaxis SQL. `startsFrom`, `startsTo`, `from` y `to` son inclusivos. Intervalos invertidos devuelven `422`. `territoryId` incluye territorios descendientes activos; `parentId` en catálogo filtra hijos directos. `skillId` filtra por habilidad vinculada, obligatoria o deseable.
- Respuestas de identidad, cuenta, recursos privados, errores y descargas: `Cache-Control: no-store`. Nunca devolver hashes, tokens de cuenta, claves de objetos, payload de outbox ni detalles internos en errores.

## Sesión y CSRF

Se adopta una sesión de servidor mediante cookie `JSESSIONID`, `Path=/`, `HttpOnly`, `SameSite=Lax` y `Secure` en producción. La API no entrega un JWT al navegador. El frontend envía cookies con `credentials: 'include'` y no guarda credenciales en `localStorage`.

1. Antes de una escritura, incluso registro, verificación, recuperación o login, llamar a `GET /auth/csrf`. Emite o utiliza una sesión anónima y devuelve `{headerName: 'X-CSRF-TOKEN', token}`.
2. Enviar ese token en la cabecera `X-CSRF-TOKEN` junto con la cookie. Una sesión anónima no concede acceso a recursos protegidos.
3. El login rota el identificador de sesión. Tras login y logout, descartar el token anterior y volver a obtenerlo; también después de expirar la sesión. Conservarlo únicamente en memoria. El filtro CSRF puede responder `403 CSRF_INVALID` antes de evaluar autenticación.
4. Un `401 AUTHENTICATION_REQUIRED` limpia el estado de cuenta del frontend. Un `403 FORBIDDEN` no significa automáticamente que la sesión venció. No reintentar automáticamente escrituras con posibles efectos tras un error de red.

Inactividad objetivo: 30 minutos. Logout y recuperación de contraseña invalidan sesiones; suspender una cuenta revoca su acceso inmediatamente. Solo cuentas `ACTIVE` verificadas acceden a producto. Credenciales incorrectas devuelven `401 INVALID_CREDENTIALS`; pendiente de correo o suspensión se comunican solo después de comprobar la contraseña.

Referencia de implementación: [Spring Security: CSRF e integración con SPA](https://docs.spring.io/spring-security/reference/servlet/exploits/csrf.html). El mecanismo anterior es una decisión del contrato de Hila; aún no está conectado a Spring Security.

## Identidad y consentimiento

`GET /policies/current` devuelve exactamente las políticas vigentes `DATA_PROCESSING` y `OPPORTUNITY_ALERTS`, incluyendo texto y versión. Los contenidos y su aprobación institucional siguen pendientes; no inventar una política legal para habilitar registros reales. Registro exige nombre, correo, contraseña de 12–128 caracteres, `adultConfirmed: true` y versión vigente del tratamiento. No se solicita fecha de nacimiento ni se permite elegir roles.

Registro crea usuario `PENDING_EMAIL`, rol `USER`, consentimiento, token hasheado y correo en outbox dentro de una transacción. Correo duplicado: misma respuesta genérica `202 VERIFICATION_REQUIRED`, sin crear otra cuenta, cambiar credenciales ni emitir una nueva sesión. Esto concreta el rechazo de duplicados sin confirmar públicamente quién tiene cuenta.

Verificación: token opaco de un solo uso, vigencia objetivo 24 horas. Recuperación: 30 minutos. Reenviar verificación invalida tokens anteriores de ese propósito; completar recuperación invalida todos los tokens de recuperación y sesiones de la cuenta. Token inválido, consumido o vencido comparte `422 TOKEN_INVALID_OR_EXPIRED`. No poner tokens en respuestas de registro ni en registros operativos. El adaptador de correo local previsto es Mailpit; aún no está incorporado a Compose.

Los enlaces de correo apuntarán a la web (`/verificar-correo` y `/restablecer-acceso`) con el token en el fragmento `#token=...`; la web lo extrae, limpia la URL y lo envía por POST. No incluirlo en query strings, analítica ni cabecera Referer. El GET del enlace nunca consume el token.

Avisos de oportunidades: `PUT /me/opportunity-alerts` guarda consentimiento explícito versionado al activar y revocación al desactivar. Desactivar no requiere aceptar una versión nueva; `policyVersion` identifica la aceptación que se revoca y debe corresponder a ella. Correos operativos de verificación, recuperación y compromisos se distinguen de esta preferencia. Una versión obsoleta al activar devuelve `409 POLICY_VERSION_OUTDATED`.

Límites iniciales de implementación: registro y solicitudes de correo, 5 intentos por 15 minutos por IP y dirección normalizada; login y consumo de tokens, 10 por 15 minutos por IP y cuenta/token hasheado. La aplicación devuelve `429 RATE_LIMITED` y `Retry-After` sin revelar si la dirección existe. Ajustes operativos conservan estos códigos y cabeceras.

## Perfil y catálogos

`GET /me` restaura la cuenta y expone roles de plataforma y `volunteerProfileExists`, sin confundirlos con roles organizacionales. El frontend usa los roles para presentar navegación; el servidor siempre vuelve a autorizar.

Un perfil inexistente devuelve `404 PROFILE_NOT_FOUND`. `PUT /me/volunteer-profile` crea o sustituye el perfil básico. Biografía, profesión, nivel educativo y modalidad preferida admiten `null`; crear perfil no afirma que todas las capacidades estén completas. Los avisos se modifican por su ruta de consentimiento, no directamente mediante el perfil.

Habilidades, disponibilidad, zonas y recursos tienen rutas propias bajo `/me`. Cada `PUT` sustituye la colección completa de forma transaccional; una lista vacía la limpia. IDs duplicados, catálogos inexistentes/inactivos y solapamientos de horario generan errores de campo. Asociaciones previas a catálogos inactivos pueden conservarse; no se pueden añadir otras nuevas. Tramos que cruzan medianoche se dividen entre los dos días. Los recursos monetarios quedan fuera del MVP.

Catálogos públicos solo exponen entradas activas. Administración puede crearlas, sustituirlas o desactivarlas; no hay borrado que rompa historial. Territorios tienen padre coherente y sin ciclos. La postulación exige un perfil existente, sin imponer una puntuación de compatibilidad mínima ni campos no acordados como condición de elegibilidad.

## Organización y autorización

Crear organización registra `PENDING`, membresía `ORG_OWNER` y evento `SUBMITTED` atómicamente. `FORMAL` exige NIT/identificador; `COLLECTIVE` admite identificador nulo y exige contexto para revisión. No se exige aprobación para tener cuenta o preparar borradores.

Miembros activos leen la organización y su historial. `ORG_OWNER` mantiene datos y equipo; `ORG_OWNER` y `ORG_COORDINATOR` administran causas, necesidades, postulaciones y seguimiento. `ADMIN` accede a lecturas privadas necesarias para revisión, auditoría y soporte; no sustituye automáticamente a coordinación para decisiones de actividad. Los códigos de rol y sus ámbitos proceden de PostgreSQL.

La incorporación al equipo usa el correo de una cuenta verificada existente y registra quién asignó el rol. No implementa invitaciones ni alta de cuentas ajenas. No puede degradarse, revocarse o perderse por concurrencia el último propietario activo. No conceder roles globales desde estas rutas.

| Estado actual | Acción | Resultado |
|---|---|---|
| `PENDING` | Aprobar / rechazar | `APPROVED` / `REJECTED` |
| `PENDING` | Solicitar información | Mantiene `PENDING`; historial `MORE_INFO` |
| `REJECTED` o `PENDING` tras `MORE_INFO` | Representante reenvía | `PENDING`; historial `SUBMITTED` |
| `APPROVED` | Suspender | `SUSPENDED` |
| `SUSPENDED` | Administración rehabilita | `APPROVED` |

Suspender una organización la oculta del catálogo y bloquea publicaciones, postulaciones y confirmaciones nuevas. Permite consultar historial, cancelar y cerrar compromisos existentes para acompañar a las personas. Toda decisión deja motivo, actor, fecha y notificación.

## Necesidades y compatibilidad

Causa: `DRAFT → ACTIVE → ARCHIVED`; no archivar con necesidades publicadas. Necesidad: `DRAFT → PUBLISHED → CLOSED | CANCELLED`; también puede cancelarse un borrador. Publicar exige organización aprobada, causa activa, inicio futuro, horario válido, cupos positivos, territorio salvo modalidad remota y riesgo `LOW` o `MODERATE`.

Los borradores usan el mismo DTO completo para mantener el MVP sencillo; no hay autoguardado parcial. El cliente mantiene los campos incompletos localmente en memoria hasta poder guardarlos. Habilidades y recursos requeridos forman parte de la sustitución del borrador/necesidad y se validan dentro de la misma transacción.

`GET /needs` solo lista necesidades `PUBLISHED` de organizaciones aprobadas y causas activas; incluye necesidades llenas con `slotsAvailable: 0`. El detalle público conserva acceso a actividades previamente publicadas `CLOSED` o `CANCELLED`, mientras su organización siga aprobada. Borradores y organizaciones suspendidas se ocultan con `404`. No hay personas, instrucciones privadas, evidencias, evaluaciones ni relato privado del resultado en estos DTO.

Coordinación usa `/needs/{id}/management` para datos privados. Con compromisos confirmados no puede cambiar horario, modalidad o territorio; debe cancelar y crear otra actividad. Los cupos nunca bajan del número ya reservado. Cambios relevantes notifican a quienes se postularon y no han rechazado/retirado su postulación.

Compatibilidad propia devuelve cinco criterios: habilidades, horario, modalidad, territorio y recursos, con `MATCH`, `PARTIAL`, `MISSING` o `NOT_APPLICABLE` y una explicación. Sin puntuación pública ni asignación automática. Para modalidad remota el territorio no aplica. Habilidades/recursos obligatorios se distinguen de deseables. La coincidencia horaria exige cubrir el tramo completo de la actividad con disponibilidad declarada; los demás casos se explican como parciales o faltantes. La fórmula de ordenación de recomendaciones queda fuera del contrato; no existe una lista opaca de personas clasificadas.

## Postulación, cupos y seguimiento

- Una postulación por persona y necesidad, incluyendo rechazadas/retiradas. Crear requiere cuenta verificada, perfil existente y necesidad publicada de organización aprobada con inicio futuro. No reserva cupo.
- `APPLIED → ACCEPTED | REJECTED | WITHDRAWN`. También se puede retirar una aceptada mientras no esté confirmada. Aceptar no asigna; la persona confirma.
- Compromiso: `NOT_CONFIRMED → CONFIRMED → COMPLETED | NO_SHOW | CANCELLED`. El servidor comprueba cupos bajo bloqueo/transacción al confirmar. Reservados = `CONFIRMED`, `COMPLETED` y `NO_SHOW`; una cancelación libera reserva. `slotsAvailable = max(0, slotsRequired - reservados)`.
- Confirmar nuevamente el mismo compromiso y repetir la misma decisión de aceptación/rechazo es idempotente. Una decisión incompatible devuelve `409 INVALID_TRANSITION`. Nunca reabrir una postulación retirada para evadir su unicidad.
- La persona o coordinación puede cancelar un compromiso confirmado con motivo opcional (`reason: null` si no se aporta). Cancelar una necesidad cancela sus compromisos aún confirmados en una transacción; conserva seguimiento ya realizado. No implica que se completó una actividad.
- Coordinación registra participación después de finalizar la actividad. `NO_SHOW` exige cero horas. Se conserva un registro por postulación; se puede corregir hasta verificarlo. Una verificación requiere horas informadas, autor y fecha; no se permite verificar horas propias.
- El cierre de una necesidad exige que no queden compromisos confirmados sin registro de participación, guarda resultado `FULFILLED | PARTIAL | NOT_FULFILLED` y responsable/fecha. No verifica automáticamente todos los registros individuales.

Las instrucciones privadas se entregan por una ruta separada, solo a la persona con compromiso confirmado o completado, coordinación y administración autorizada. Las postulaciones y perfiles de candidatos son privados para la organización responsable; nunca exponerlos en el catálogo.

## Evidencias, feedback y notificaciones

Evidencias: `multipart/form-data`, un archivo por solicitud, máximo 20 MiB, JPEG/PNG/PDF con validación del contenido real. Las sube coordinación, vinculadas a una necesidad cerrada o un registro de participación. El propietario puede leer evidencia de su participación; coordinación y administración autorizada pueden acceder al recurso correspondiente. El catálogo público nunca expone archivos ni claves de almacenamiento. La descarga se autoriza antes de emitir URL firmada con vigencia máxima de 60 segundos. Retención y eliminación administrativa deberán seguir la política aprobada; no se incorpora un borrado público a este MVP.

Feedback: tras participación verificada y compromiso completado o inasistencia, un feedback por autor/postulación. El servidor deriva el destinatario. Quien se postuló evalúa la organización; coordinación evalúa al voluntario, sin autoevaluación. Solo autor, coordinación responsable y administración pueden leerlo. La persona evaluada no obtiene acceso automático al comentario de coordinación. No hay ranking, promedio público ni modificación del feedback desde el navegador.

La bandeja de notificaciones es propia. Marcar como leído es idempotente. Correos se solicitan mediante outbox con reintentos; una falla de SMTP no revierte una decisión ya confirmada. Eventos de cuenta, revisión organizacional, decisiones, compromisos y cambios/cierre de actividad deben ser auditables. Los avisos de nuevas oportunidades solo se envían con consentimiento activo y compatibilidad básica; no prometen una asignación.

## Errores

Todos los errores HTTP de la API, incluidos los filtros de seguridad, usan `{code, message, fieldErrors, traceId}`. `fieldErrors` es `[]` cuando no aplica. Las rutas de campo usan nombres del DTO, por ejemplo `items[0].skillId`. Los códigos son estables; los mensajes pueden mejorar sin romper la integración. Los clientes no deben interpretar el texto como código.

| HTTP | Códigos |
|---|---|
| 400 | `MALFORMED_REQUEST` |
| 401 | `AUTHENTICATION_REQUIRED`, `INVALID_CREDENTIALS` |
| 403 | `CSRF_INVALID`, `FORBIDDEN`, `EMAIL_NOT_VERIFIED`, `ACCOUNT_SUSPENDED` |
| 404 | `RESOURCE_NOT_FOUND`, `PROFILE_NOT_FOUND` |
| 409 | `POLICY_VERSION_OUTDATED`, `INVALID_TRANSITION`, `NO_SLOTS_AVAILABLE`, `APPLICATION_EXISTS`, `MEMBERSHIP_EXISTS`, `LAST_OWNER_REQUIRED`, `LAST_ADMIN_REQUIRED`, `CATALOG_ENTRY_EXISTS` |
| 413 / 415 | `FILE_TOO_LARGE` / `UNSUPPORTED_MEDIA_TYPE` |
| 422 | `VALIDATION_FAILED`, `TOKEN_INVALID_OR_EXPIRED`, `MEMBER_UNAVAILABLE` |
| 429 | `RATE_LIMITED` con cabecera `Retry-After` |
| 500 | `INTERNAL_ERROR` |
| 503 | `STORAGE_UNAVAILABLE` (carga de imágenes; se conserva la anterior) |

Errores de campo: `REQUIRED`, `INVALID_FORMAT`, `TOO_LONG`, `OUT_OF_RANGE`, `MUST_BE_TRUE`, `DUPLICATE`, `OVERLAPPING`, `INACTIVE_REFERENCE`, `INVALID_REFERENCE`. Invariantes como último propietario, publicación o unicidad se comprueban también en transacción. A recursos privados ajenos se responde `404`, evitando revelar su existencia; `403` se reserva para acciones no permitidas sobre un recurso visible. Los esquemas de respuesta comunes enumeran categorías posibles; las descripciones de operación especifican los conflictos relevantes.

## Cobertura del MVP

| Requisito de REQUISITOS.md | Contrato |
|---|---|
| 1. Cuenta, verificación, recuperación, consentimiento | `/auth/*`, `/policies/current`, `/me`, `/me/consents` |
| 2. Capacidades | `/me/volunteer-profile`, `/me/skills`, `/me/availability`, `/me/service-areas`, `/me/resources`, `/me/opportunity-alerts` |
| 3. Organizaciones y colectivos | `/organizations/*`, `/me/organizations`, `/admin/organizations/*` |
| 4. Causas y necesidades | `/organizations/{id}/causes`, `/causes/*`, `/needs/*/management`, publicación/cancelación/cierre |
| 5. Catálogo público | `/needs`, `/needs/{id}`, `/catalogs/*` |
| 6. Compatibilidad explicable | `/needs/{id}/compatibility` |
| 7. Postulaciones y cupos | `/needs/{id}/applications`, `/me/applications`, `/applications/*` |
| 8. Seguimiento y adjuntos | `/applications/{id}/participation`, `/participation/*`, `/needs/{id}/outcome`, evidencias |
| 9. Feedback privado | `/applications/{id}/feedback` |
| 10. Notificaciones y correo | `/me/notifications*`, outbox y preferencias versionadas |
| 11. Administración y trazabilidad | `/admin/organizations`, `/admin/catalogs/*`, `/admin/users*`, `/admin/audit-events` |

El actor administrador menciona atención de reportes, pero no hay flujo, estados ni tabla de reportes definidos en los requisitos del MVP. No se inventa una API de moderación en esta versión. Si se incorpora ese flujo, deberá ampliarse primero el dominio y después el contrato.

## Estado y límites de esta entrega

La entrega implementa acceso y perfiles básicos, organizaciones, revisión y publicación, con almacenamiento de imágenes local/R2. Las rutas verificadas de identidad, perfiles, revisión e imágenes se marcan `implemented`; las capacidades fuera de esta entrega conservan `planned`. El resto del MVP (habilidades, horarios, recursos, causas, necesidades, postulaciones, seguimiento, feedback, evidencias y bandeja de notificaciones) conserva su contrato objetivo y permanece pendiente.

La migración V3 agrega sesiones JDBC, modalidad preferida y visibilidad, metadatos de imágenes y límites de identidad, sin editar V1/V2. Los correos usan outbox con token entregable cifrado; `account_tokens` conserva solo su hash. Las políticas de desarrollo no habilitan tratamiento de datos reales: la producción requiere contenido aprobado y configuración explícita.

Swagger `/v3/api-docs` describe los controladores implementados. Este archivo versionado cubre además el objetivo completo del MVP; una operación `planned` no constituye una promesa de disponibilidad. La integración debe contrastar cuerpos, respuestas, errores y permisos, además de la presencia de las rutas.

## Validar y coordinar con el frontend

Validación local (Node.js 24; la CLI se fija también en CI):

```powershell
npx --yes @redocly/cli@2.73.0 lint openapi/hila-v1.json
```

El frontend mantiene una copia del contrato para generar tipos sin depender de un backend ejecutándose. Se actualiza desde este archivo mediante `npm run api:sync` y se valida con `npm run api:check`; consultar su guía de integración. El hash identifica exactamente qué contrato consume. No editar tipos generados ni tratar la copia como otra fuente de verdad.

La versión es un borrador revisable de contrato, no una declaración de disponibilidad. Cambios de campos, rutas o estados se coordinan entre repositorios y actualizan versión, copia y tipos en el mismo trabajo. Una vez implementado y consumido un flujo, cualquier ruptura requiere coordinación explícita y, si no hay migración compatible, una nueva versión de API. Las ampliaciones compatibles conservan `/api/v1`.

Formato normativo: [OpenAPI 3.0.3](https://spec.openapis.org/oas/v3.0.3.html). Las decisiones de producto de esta guía concretan las ambigüedades del MVP y deben revisarse con el equipo antes de habilitar datos reales.


## Entrega de acceso y perfiles: publicación e imágenes

La entrega de perfiles básicos amplía el primer flujo con organizaciones, recuperación de acceso, publicación voluntaria y revisión administrativa. Las operaciones mantienen `planned` hasta verificarse contra la implementación; el estado por operación del OpenAPI es la referencia de disponibilidad.

Una cuenta puede crear perfil voluntario y representar organizaciones independientemente. Ambos perfiles nacen `PRIVATE`. `PUT /me/volunteer-profile/publication` y `PUT /organizations/{organizationId}/publication` reciben `{visible: boolean}` y devuelven el perfil privado actualizado. Publicar una organización requiere aprobación; ocultarla siempre está permitido a su propietario autorizado. Un rechazo, suspensión o cuenta no activa retira su acceso público.

Los perfiles públicos se consultan por `GET /volunteers/{userId}/public-profile` y `GET /organizations/{organizationId}/public-profile`. No se ofrece directorio ni buscador en esta entrega. El DTO voluntario público incluye únicamente identificador, nombre, presentación, profesión, modalidad e imágenes; excluye correo, educación y preferencias de avisos. El DTO organizacional público incluye nombre, tipo, descripción, territorio, contactos declarados públicos e imágenes, con estado `APPROVED`; excluye NIT, representantes y contexto/historial de revisión. Perfil oculto o no habilitado: `404 RESOURCE_NOT_FOUND`.

Los perfiles privados incluyen `visibility` (`PRIVATE | PUBLIC`), `avatar` y `cover`. Las imágenes usan `ImageRef`: `{id, url, width, height, contentType}`; una imagen inexistente se representa como `null`. El cliente restaura iniciales y portada de Hila cuando corresponda.

- `PUT /me/volunteer-profile/images/{slot}` y `PUT /organizations/{organizationId}/images/{slot}`: multipart con un `file`, `slot=avatar|cover`, respuesta `200 ImageRef`.
- `DELETE` en esas mismas rutas: `204`, idempotente si ya no hay imagen. El acceso requiere titular del perfil o propietario de la organización, cuenta activa y CSRF.
- JPEG/PNG únicamente, máximo 10 MiB y 16 megapíxeles. Se comprueba el contenido real, se normaliza orientación y se recodifica sin metadatos. Avatar cuadrado hasta 512×512; portada 3:1 hasta 1800×600, con recorte centrado y sin distorsión.
- Un reemplazo fallido conserva la imagen anterior. Los objetos reemplazados o huérfanos se eliminan con limpieza reintentable; PostgreSQL mantiene propiedad y estado, no el archivo binario.
- `GET /profile-images/{imageId}` entrega bytes JPEG/PNG con `Cache-Control: no-store`. Autoriza la lectura privada por sesión o la pública por la visibilidad vigente del perfil. Ocultar/suspender también impide leer sus imágenes públicamente; IDs reemplazados o eliminados devuelven `404`.

El almacenamiento se selecciona explícitamente por configuración: archivos locales para desarrollo o adaptador S3 compatible con R2. El bucket R2 permanece privado y sus credenciales solo existen en el backend. Los errores de R2 no provocan fallback silencioso a disco local. No se mezclan estas imágenes de presentación con evidencias privadas de participación.
