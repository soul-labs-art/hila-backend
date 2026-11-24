# Hila Backend

Hila es una iniciativa de tecnología cívica que busca conectar las capacidades de las personas —su tiempo, conocimientos, oficios y recursos— con necesidades concretas de organizaciones y colectivos comunitarios. Está en preparación un piloto en Medellín y el Valle de Aburrá. Hila apoya la coordinación y el seguimiento; las organizaciones conservan la decisión y responsabilidad sobre sus actividades.

Este repositorio contiene el backend de Hila: API, reglas de negocio y persistencia relacional. La interfaz web vive en [hila-frontend](https://github.com/soul-labs-art/hila-frontend).

## Responsabilidad y estado

Este servicio es dueño del contrato HTTP, la autorización, las reglas del dominio y el esquema PostgreSQL. Flyway gestiona las migraciones. No contiene la interfaz web.

El backend implementa las 95 operaciones del MVP, desde la cuenta y los perfiles hasta la publicación de oportunidades, postulaciones, cupos, cierre, evidencias, feedback, notificaciones y administración. Spring Security aplica sesión JDBC, CSRF y roles de plataforma. Los servicios comprueban la cuenta, la organización y el permiso sobre cada recurso privado.

El [contrato OpenAPI 1.0.0](openapi/hila-v1.json) es la fuente para la [entrega al frontend](docs/ENTREGA_FRONTEND.md) y la generación de sus tipos. Consulta las [reglas de acceso y transiciones](docs/API_CONTRACT.md). Swagger describe los controladores del servidor local.

## Tecnologías

- Java 17 y Spring Boot
- PostgreSQL 18, Spring Data JPA y Flyway
- Spring Security, Bean Validation, Actuator y OpenAPI
- Maven Wrapper y Docker Compose

## Ejecutar con Docker Compose

Requisitos: Docker Engine con Docker Compose y conexión a internet para descargar las imágenes y dependencias en el primer inicio.

```powershell
Copy-Item .env.example .env
docker compose up --build
```

Compose construye la imagen de la API desde el [Dockerfile](Dockerfile) y levanta la API Spring Boot, PostgreSQL y Mailpit. La API espera a que la base esté lista y Flyway aplica las migraciones al iniciar.

- Salud de la API: <http://127.0.0.1:8080/api/v1/health>
- Swagger UI: <http://127.0.0.1:8080/swagger-ui/index.html>
- PostgreSQL para conexiones locales: `127.0.0.1:5433` (solo loopback)

Compose conserva los datos en el volumen `postgres_data`. `docker compose down` detiene los servicios y conserva ese volumen; `docker compose down -v` también elimina la base local.

## Piloto en Railway

Proyecto `bioma-app`, ambiente `Hila`: `hila-backend`, `hila-frontend`,
`Postgres-kZHd` y `hila-mailpit`. Web: <https://hila-frontend-hila.up.railway.app>.
Usar perfiles ficticios; las políticas están marcadas como piloto. API y base
se comunican por red privada, sin dominio público de API ni proxy TCP de base.
Flyway migra al iniciar. Las imágenes persisten en `/app/media`; base e imágenes
tienen respaldos diarios y semanales configurados en Railway.

`railway.json` configura Docker, salud y reinicio. Railway sigue `main` del repo:
al integrar cambios, despliega automáticamente. Para despliegue manual:

```sh
railway up --project 25124fb1-18bd-4748-8579-a732a9386573 --environment Hila --service hila-backend --detach
```

Variables: conexión JDBC y credenciales referenciadas desde PostgreSQL,
`OUTBOX_ENCRYPTION_KEY` aleatoria, `WEB_ORIGIN` con la URL HTTPS de la web,
`STORAGE_PROVIDER=local`, `STORAGE_DIRECTORY=/app/media`, `SERVER_ADDRESS=::`,
`SERVER_PORT=8080`, `SERVER_FORWARD_HEADERS_STRATEGY=framework`. Swagger está desactivado.
Brevo usa `SMTP_HOST=smtp-relay.brevo.com`, `SMTP_PORT=587`, `SMTP_AUTH=true`,
`SMTP_STARTTLS=true`, `SPRING_MAIL_USERNAME` (Login SMTP), `SPRING_MAIL_PASSWORD`
(clave SMTP) y `SMTP_FROM=Hila <hila@soul-labs.art>`. Los secretos viven en Railway.
El buzón protegido <https://hila-mailpit-hila.up.railway.app> queda disponible
para pruebas aisladas; no recibe el correo enviado por Brevo. Su base persiste
en `/data` y su acceso se configura con `MP_UI_AUTH`.

No activar `prod` sin cumplir los requisitos de producción descritos abajo.

## Pruebas locales

Con Docker Compose iniciado para disponer de PostgreSQL, ejecuta desde PowerShell:

```powershell
.\mvnw.cmd -B verify
```

En Linux o macOS:

```sh
./mvnw -B verify
```

Flyway administra el esquema y Hibernate valida el mapeo sin modificar la base. El workflow de GitHub Actions ejecuta esta verificación contra PostgreSQL.

## Documentación

- [Índice y responsables de documentación](docs/README.md)
- [Contexto del proyecto](docs/CONTEXTO_PROYECTO.md)
- [Requisitos](docs/REQUISITOS.md)
- [Arquitectura y reglas del backend](docs/ARQUITECTURA_Y_REGLAS.md)
- [Flujos](docs/FLUJOS.md)
- [Primer flujo de desarrollo](docs/PRIMER_FLUJO_VERTICAL.md)
- [Modelo de datos](docs/MODELO_DATOS.md)
- [Identidad visual y guía de interfaz](https://github.com/soul-labs-art/hila-frontend/tree/main/docs)

## Licencia

El código fuente de Hila creado por Soul Labs Art en este repositorio se ofrece bajo la [PolyForm Noncommercial License 1.0.0](LICENSE). Permite usar, modificar y redistribuir el software con fines no comerciales; no concede permiso para el uso comercial. Para ese uso se requiere autorización independiente de Soul Labs Art.

La licencia cubre el software de Hila indicado arriba. Las dependencias y demás recursos de terceros pueden tener términos propios; consulta sus avisos y licencias.

## Probar acceso y perfiles

Compose incluye Mailpit: abre <http://127.0.0.1:8025> para leer verificación y recuperación locales. Los enlaces apuntan al frontend definido por `WEB_ORIGIN` (por defecto `http://127.0.0.1:5173`). El trabajador procesa el outbox cada tres segundos con reintentos; cifra los tokens con AES-GCM y elimina la carga tras enviar. `account_tokens` solo conserva SHA-256. El entorno predeterminado usa políticas `dev-1`, únicamente para datos de prueba.

Las sesiones duran 30 minutos de inactividad, se guardan en PostgreSQL y conservan cookie `JSESSIONID`. Obtener `/api/v1/auth/csrf` antes de una escritura pública o privada; renovar después de login/logout. Registro y solicitudes de correo responden genéricamente para no enumerar cuentas.

Para conceder administración a una cuenta **ya verificada**, inicia temporalmente la API con `--hila.admin-bootstrap.email=correo@example.test`, confirma el resultado y reinicia sin ese argumento. El comando no está habilitado por defecto, no crea cuentas y no existe una ruta HTTP para conceder roles. Desde la web se pueden revisar organizaciones, aprobar, rechazar, pedir información o suspender/rehabilitar.

Para recuperar una organización sin propietarios activos, ejecuta una instancia de mantenimiento con
`--spring.main.web-application-type=none --hila.owner-recovery.actor=<uuid-admin> --hila.owner-recovery.organization=<uuid-org> --hila.owner-recovery.user=<uuid-propietario>`.
Usa la misma configuración de base de datos de la API. El administrador y la cuenta de reemplazo deben estar activos y verificados. La operación comprueba que no haya propietarios activos, asigna la membresía y guarda un evento de auditoría. La suspensión conserva las membresías existentes para una eventual rehabilitación.

### Imágenes, evidencias y R2

`STORAGE_PROVIDER=local` guarda imágenes en `.hila-media` (o `STORAGE_DIRECTORY`); Docker conserva `/app/media` en `profile_media`. `STORAGE_PROVIDER=r2` requiere `R2_ENDPOINT`, `R2_BUCKET`, `R2_ACCESS_KEY_ID` y `R2_SECRET_ACCESS_KEY`. El bucket permanece privado, con permisos de objeto limitados al bucket y región SDK `auto`. Nunca colocar credenciales en el frontend. No hay fallback a local si R2 falla.

JPEG/PNG hasta 10 MiB y 16 MP se decodifican, orientan y recodifican a PNG sin metadatos, con recorte centrado. Avatar máximo 512×512; portada máximo 1800×600. La API devuelve `ImageRef` y sirve el contenido solo si el actor puede leerlo o el perfil está público. Ocultar el perfil retira también el acceso público a imágenes. Los objetos antiguos o fallidos se eliminan mediante limpieza reintentable cada minuto; los pendientes abandonados caducan tras una hora.

Las evidencias usan el mismo proveedor de almacenamiento en un espacio de claves privado. Se descargan mediante enlaces firmados de máximo 60 segundos y la API vuelve a comprobar sesión y acceso. Se aceptan JPEG, PNG y PDF hasta 20 MiB. Los avatares y portadas conservan su límite de 10 MiB.

Producción exige `POLICY_VERSION` distinta de `dev-*`, `POLICY_CONTENT` y `ALERTS_POLICY_CONTENT` aprobados, `OUTBOX_ENCRYPTION_KEY` secreto y R2 configurado. Deben configurarse también el remitente `SMTP_FROM`, SMTP y el proxy HTTPS de origen compartido. SMTP autenticado admite `SPRING_MAIL_USERNAME`, `SPRING_MAIL_PASSWORD` y las propiedades de TLS de Spring Mail. El perfil `prod` rechaza la configuración local por defecto. Esta entrega no despliega producción ni suministra documentos legales aprobados.

### Verificación

`./mvnw -B verify` verifica las rutas del contrato y las respuestas HTTP de los flujos del MVP, además de concurrencia de cupos, privacidad, recuperación de propiedad, reintentos de correo y actualización de una base con datos V3. La suite usa PostgreSQL y el adaptador de archivos local. La aceptación en un entorno compartido incluye probar un bucket R2 y un servicio SMTP configurados.
