# Hila Backend

Hila es una iniciativa de tecnología cívica que busca conectar las capacidades de las personas —su tiempo, conocimientos, oficios y recursos— con necesidades concretas de organizaciones y colectivos comunitarios. Está en preparación un piloto en Medellín y el Valle de Aburrá. Hila apoya la coordinación y el seguimiento; las organizaciones conservan la decisión y responsabilidad sobre sus actividades.

Este repositorio contiene el backend de Hila: API, reglas de negocio y persistencia relacional. La interfaz web vive en [hila-frontend](https://github.com/soul-labs-art/hila-frontend).

## Responsabilidad y estado

Este servicio es dueño del contrato HTTP, la autorización, las reglas del dominio y el esquema PostgreSQL. Flyway gestiona las migraciones. No contiene la interfaz web.

La base técnica, las migraciones iniciales, los roles relacionales y el endpoint de salud están preparados. Es un punto de partida para el MVP, no una plataforma completa: registro, autenticación e implementación de los flujos de producto siguen pendientes. Los roles existen en la base de datos; su integración con Spring Security se hará al implementar identidad.

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
- [Modelo de datos](docs/MODELO_DATOS.md)
- [Identidad visual y guía de interfaz](https://github.com/soul-labs-art/hila-frontend/tree/main/docs)
