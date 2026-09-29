# Hila Backend

Hila es una plataforma cívica que conecta capacidades de personas voluntarias con necesidades concretas de organizaciones y colectivos comunitarios. Este repositorio contiene la API y la persistencia de Hila: Spring Boot, PostgreSQL y migraciones Flyway.

La interfaz vive en [hila-frontend](https://github.com/soul-labs-art/hila-frontend). Este repositorio es dueño del contrato HTTP, las reglas del servidor y el modelo de datos.

## Estado

La base inicial, las migraciones, los roles relacionales y el endpoint de salud están preparados. El registro, la autenticación y las funciones de producto se implementarán por etapas. Los roles ya están en PostgreSQL; la integración que los cargará en Spring Security se implementará junto con identidad.

## Tecnologías

- Java 17 y Spring Boot
- PostgreSQL 18, Spring Data JPA y Flyway
- Spring Security, Bean Validation, Actuator y OpenAPI
- Maven Wrapper y Docker Compose

## Desarrollo local

Requisitos: Docker Compose y, para ejecutar pruebas directamente, Java 17.

```powershell
Copy-Item .env.example .env
docker compose up --build
```

- API: <http://127.0.0.1:8080/api/v1/health>
- Swagger: <http://127.0.0.1:8080/swagger-ui/index.html>
- PostgreSQL: `127.0.0.1:5433` (solo loopback)

Para ejecutar las pruebas, inicia PostgreSQL y usa `./mvnw -B verify` (Linux/macOS) o `.\mvnw.cmd -B verify` (PowerShell). Flyway administra el esquema; Hibernate valida el mapeo sin modificar la base.

`docker compose down` conserva el volumen local. `docker compose down -v` también elimina esa base de desarrollo.

## Documentación

- [Índice y responsables de documentación](docs/README.md)
- [Contexto del proyecto](docs/CONTEXTO_PROYECTO.md)
- [Requisitos](docs/REQUISITOS.md)
- [Arquitectura y reglas del backend](docs/ARQUITECTURA_Y_REGLAS.md)
- [Flujos](docs/FLUJOS.md)
- [Modelo de datos](docs/MODELO_DATOS.md)
- [Identidad visual y guía de interfaz](https://github.com/soul-labs-art/hila-frontend/tree/main/docs)
