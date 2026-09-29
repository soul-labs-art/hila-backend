# Hila Backend: contexto y reglas

## Responsabilidad

Este repositorio es dueño de la API, sus reglas de negocio, persistencia, migraciones y documentación de dominio. La interfaz React se mantiene en [hila-frontend](https://github.com/soul-labs-art/hila-frontend). No copies la aplicación frontend ni sus dependencias a este repositorio.

Hila articula capacidades ciudadanas con necesidades sociales concretas en Medellín y el Valle de Aburrá. Las organizaciones mantienen la responsabilidad de decidir y acompañar sus actividades. Consulta `docs/CONTEXTO_PROYECTO.md`, `docs/REQUISITOS.md` y `docs/FLUJOS.md` antes de cambiar comportamiento.

## Documentación del repositorio

`docs/README.md` indica la fuente de verdad de cada tema. Este repositorio conserva contexto, requisitos, flujos, arquitectura de backend y modelo relacional/Flyway. La identidad, el sistema visual y las decisiones de interfaz viven en el repositorio frontend.

## Reglas de implementación

- Mantener un monolito modular Spring Boot; no introducir microservicios sin una necesidad operativa demostrada.
- Versionar endpoints bajo `/api/v1`. Los controladores validan solicitudes y delegan en casos de uso; no exponen entidades JPA ni contienen reglas de dominio.
- PostgreSQL es la fuente de verdad. Flyway es el único dueño del esquema. Nunca usar `ddl-auto=update` ni reescribir migraciones ya compartidas; cada cambio se agrega en una migración nueva.
- Expresar integridad con claves foráneas, restricciones, índices y transacciones, además de validaciones de aplicación.
- Mantener roles en `roles`; asignar roles de plataforma mediante `user_roles` y roles organizacionales mediante membresías con ámbito válido. La conexión de autenticación con Spring Security aún está pendiente.
- Mantener la autorización en el servidor. Proteger datos personales, postulaciones, feedback y evidencias; registrar acciones sensibles sin guardar secretos.
- Documentar el contrato mediante OpenAPI. Coordinar cambios incompatibles con el repositorio frontend.
- Contraseñas, tokens y datos reales no se guardan en Git. `.env.example` solo contiene credenciales locales de desarrollo.
- Actualizar contexto, requisitos, flujos y modelo de datos cuando un cambio afecte su comportamiento.
- Validar con `./mvnw -B verify`. Los cambios de esquema se comprueban desde una base vacía y preservando datos existentes cuando corresponda.

## Flujo de trabajo

- Crear ramas cortas `feat/...`, `fix/...` o `docs/...`; integrar mediante pull request a `main`.
- Cada pull request explica el cambio, riesgos, migraciones y forma de validarlo.
- No añadir dependencias o infraestructura sin un caso de uso explícito.

## Referencias

- [Cómo ejecutar y probar](README.md)
- [Arquitectura](docs/ARQUITECTURA_Y_REGLAS.md)
- [Modelo de datos](docs/MODELO_DATOS.md)
- [Requisitos y flujos](docs/REQUISITOS.md) · [Flujos](docs/FLUJOS.md)
