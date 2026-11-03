# Hila Backend

Hila es una iniciativa de tecnología cívica que busca conectar las capacidades de las personas —su tiempo, conocimientos, oficios y recursos— con necesidades concretas de organizaciones y colectivos comunitarios. Está en preparación un piloto en Medellín y el Valle de Aburrá. Hila apoya la coordinación y el seguimiento; las organizaciones conservan la decisión y responsabilidad sobre sus actividades.

Este repositorio contiene el backend de Hila: API, reglas de negocio y persistencia relacional. La interfaz web vive en [hila-frontend](https://github.com/soul-labs-art/hila-frontend).

## Responsabilidad y estado

Este servicio es dueño del contrato HTTP, la autorización, las reglas del dominio y el esquema PostgreSQL. Flyway gestiona las migraciones. No contiene la interfaz web.

Se implementaron registro adulto, verificación y recuperación por correo, sesión JDBC y CSRF, perfil voluntario básico, organizaciones y revisión administrativa, publicación voluntaria e imágenes. Las capacidades ampliadas, oportunidades y participación siguen previstas en el contrato. Los roles se cargan desde PostgreSQL; cada operación privada comprueba cuenta y ámbito del recurso.

El [contrato OpenAPI del MVP](openapi/hila-v1.json) define las operaciones objetivo y sus DTO para coordinar el trabajo del frontend. Consulta [estados, errores, permisos y diferencias pendientes](docs/API_CONTRACT.md). Swagger en ejecución sigue describiendo únicamente los controladores implementados.

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

Compose construye la imagen de la API desde el [Dockerfile](Dockerfile) y levanta dos servicios: `api` (Spring Boot) y `db` (PostgreSQL). La API espera a que la base esté lista y Flyway aplica las migraciones al iniciar.

- Salud de la API: <http://127.0.0.1:8080/api/v1/health>
- Swagger UI: <http://127.0.0.1:8080/swagger-ui/index.html>
- PostgreSQL para conexiones locales: `127.0.0.1:5433` (solo loopback)

Compose conserva los datos en el volumen `postgres_data`. `docker compose down` detiene los servicios y conserva ese volumen; `docker compose down -v` también elimina la base local.

## Pruebas

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

### Imágenes y R2

`STORAGE_PROVIDER=local` guarda imágenes en `.hila-media` (o `STORAGE_DIRECTORY`); Docker conserva `/app/media` en `profile_media`. `STORAGE_PROVIDER=r2` requiere `R2_ENDPOINT`, `R2_BUCKET`, `R2_ACCESS_KEY_ID` y `R2_SECRET_ACCESS_KEY`. El bucket permanece privado, con permisos de objeto limitados al bucket y región SDK `auto`. Nunca colocar credenciales en el frontend. No hay fallback a local si R2 falla.

JPEG/PNG hasta 10 MiB y 16 MP se decodifican, orientan y recodifican a PNG sin metadatos, con recorte centrado. Avatar máximo 512×512; portada máximo 1800×600. La API devuelve `ImageRef` y sirve el contenido solo si el actor puede leerlo o el perfil está público. Ocultar el perfil retira también el acceso público a imágenes. Los objetos antiguos o fallidos se eliminan mediante limpieza reintentable cada minuto; los pendientes abandonados caducan tras una hora.

Producción exige `POLICY_VERSION` distinta de `dev-*`, `POLICY_CONTENT` y `ALERTS_POLICY_CONTENT` aprobados, `OUTBOX_ENCRYPTION_KEY` secreto y R2 configurado. Deben configurarse también SMTP y el proxy HTTPS/origen compartido. El perfil `prod` rechaza la configuración local por defecto. Esta entrega no despliega producción ni suministra documentos legales aprobados.

### Verificación

`./mvnw -B verify` incluye pruebas de cuenta, tokens usados, recuperación, perfiles privados/públicos, aislamiento organizacional, revisión e imágenes. El adaptador R2 requiere una prueba de carga/lectura/borrado con credenciales de un bucket de desarrollo antes de usar datos reales; sin esas credenciales las pruebas utilizan archivos locales. Flyway V3 conserva V1/V2 y agrega sesiones, visibilidad, modalidad, metadatos de imagen y límites de intentos.
