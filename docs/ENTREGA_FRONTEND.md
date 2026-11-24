# Integrar el MVP de Hila con el frontend

El [OpenAPI 1.0.0](../openapi/hila-v1.json) cubre las 95 operaciones de `/api/v1`. Usa el archivo del mismo commit de backend que se ejecute durante la integración.

1. Con los dos checkouts lado a lado, ejecuta en `hila-frontend`:

   ```sh
   npm ci
   npm run api:sync -- ../hila-backend/openapi/hila-v1.json
   npm run api:check
   npm run lint
   npm run build
   ```

2. Conecta a la API los módulos de capacidades, Explorar, actividad y postulaciones, causas y necesidades, participación y cierre, Equipo, Avisos y administración. Quita el requisito de `VITE_API_MOCK` de los enlaces, módulos y rutas que integran estas áreas. Actualiza los mocks de desarrollo para reflejar las reglas del contrato.
3. Permite visitas sin sesión a Explorar y al detalle de la oportunidad. El cálculo de compatibilidad requiere sesión y perfil. Mantén el login para postularse y el control de acceso a gestión, instrucciones, participación y feedback. El backend vuelve a comprobar cada permiso.
4. Mantén las cookies de origen compartido y renueva el token CSRF después de login y logout. En Privacidad, el mismo cuerpo de solicitud sirve para desactivar avisos incluso tras actualizar la política.
5. En el proxy web, cambia el límite de solicitud de 11 MiB a 21 MiB o más para que un archivo de evidencia de 20 MiB entre con el encabezado multipart. Las imágenes de evidencia admiten hasta 32 megapíxeles. Conserva 10 MiB y 16 megapíxeles como límites de avatares y portadas. Para descargar, usa el enlace devuelto por la API antes de que expire y deja que el navegador envíe la cookie del mismo origen.

Comprueba con PostgreSQL, Mailpit y el cliente web el recorrido de una organización aprobada que publica una convocatoria. Una persona anónima puede verla. Tras iniciar sesión se postula, la organización acepta, la persona confirma y la organización registra y verifica la participación. Valida también el acceso privado a las evidencias y el caso de una convocatoria sin cupos.
