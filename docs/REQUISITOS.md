# Requisitos iniciales de Hila

## Propósito

Ayudar a organizaciones y colectivos comunitarios a describir necesidades sociales concretas y conectar esas necesidades con capacidades voluntarias disponibles en Medellín y el Valle de Aburrá. Hila facilita información y seguimiento; las decisiones y la ejecución siguen a cargo de las personas y organizaciones.

Las brechas descritas en el planteamiento (capacidades dispersas, convocatorias ambiguas y coordinación manual) son hipótesis de investigación. El piloto deberá comprobar cuáles aparecen y con qué frecuencia.

**Pregunta de investigación:** ¿Cómo diseñar e implementar una plataforma web que articule capacidades, habilidades y disponibilidad de personas voluntarias con necesidades sociales específicas de organizaciones y comunidades de Medellín y el Valle de Aburrá, mediante criterios de compatibilidad, seguimiento y trazabilidad?

**Objetivo general:** implementar una plataforma web que facilite ese encuentro mediante compatibilidad, calificación privada, seguimiento y trazabilidad.

Los objetivos específicos son levantar requisitos con personas y organizaciones; diseñar arquitectura e información para perfiles, necesidades, disponibilidad y ubicación; implementar la gestión de cuentas, oportunidades, postulaciones y seguimiento; y validar usabilidad, compatibilidad y carga administrativa con un piloto territorial.

## Actores

- **Persona voluntaria adulta:** crea cuenta y perfil por su cuenta, declara habilidades, horarios, modalidad, territorio y recursos que puede aportar; consulta oportunidades y decide a cuáles postularse.
- **Representante de una organización formal:** crea una cuenta personal, registra una organización y solicita verificación para publicar necesidades.
- **Representante de un colectivo comunitario:** sigue el mismo flujo, declara que es un colectivo sin personería o NIT y describe su trayectoria, actividades y referencias de contexto para revisión manual. No se exige constituirse legalmente para participar en el piloto.
- **Coordinador de organización:** administra causas y necesidades, evalúa postulaciones, confirma compromisos y registra resultados.
- **Administrador de Hila:** revisa solicitudes de organizaciones, mantiene catálogos, atiende reportes y puede suspender cuentas.

La cuenta voluntaria no requiere que exista una organización aprobada. Puede completar su perfil mientras se incorporan organizaciones. Si el catálogo aún no tiene actividades, la plataforma mostrará ese estado y permitirá activar avisos de nuevas oportunidades por correo, con consentimiento. Crear una cuenta organizacional tampoco exige que ya haya voluntarios registrados.

## Alcance del MVP

1. Registro de adultos, verificación de correo, inicio/cierre de sesión, recuperación de acceso y aceptación versionada de la política de tratamiento.
2. Perfil voluntario con habilidades, profesión/oficio opcional, disponibilidad semanal, modalidad, zonas de servicio y recursos no monetarios sencillos (por ejemplo, vehículo o equipo).
3. Registro autogestionado de organizaciones formales y colectivos. El administrador revisa y aprueba, rechaza o suspende. Solo una organización aprobada puede publicar.
4. Causas y necesidades separadas. Una causa agrupa necesidades; cada necesidad describe actividad, resultado esperado, habilidades y recursos, fecha/horario, modalidad, territorio, condiciones, riesgo bajo/moderado y cupos.
5. Catálogo público de necesidades sin datos personales ni instrucciones sensibles. Para postularse se requiere cuenta verificada.
6. Compatibilidad basada en criterios visibles: habilidades, horario, modalidad, territorio y recursos. Mostrar coincidencias y faltantes; nunca confirmar una asignación automáticamente.
7. Postulación, aceptación/rechazo por una organización, confirmación del compromiso, cancelación y control transaccional de cupos.
8. Registro de horas, participación y resultado por necesidad. La organización verifica el cierre; se permiten adjuntos privados con acceso autenticado.
9. Feedback estructurado y privado entre las partes, visible solo a coordinación autorizada y administración. No hay puntuación pública de personas ni organizaciones.
10. Notificaciones en la aplicación y correo para verificación, postulación, decisión, cambios de actividad y avisos de nuevas oportunidades.
11. Panel administrativo mínimo para revisar organizaciones, gestionar catálogos y consultar trazabilidad.

## Reglas funcionales

- El alta de una persona voluntaria es independiente del alta y verificación de organizaciones.
- Una organización nace como `PENDING`; solo `APPROVED` puede publicar. Un colectivo puede participar sin NIT, sujeto a revisión manual.
- Cada cuenta puede ser voluntaria y pertenecer a una o más organizaciones mediante membresías explícitas.
- Los roles de plataforma y organización se asignan desde el catálogo `roles` en PostgreSQL; las membresías solo aceptan roles de ámbito organizacional.
- Solo mayores de 18 años pueden registrarse en el piloto; almacenar una declaración de mayoría de edad y su fecha, sin pedir fecha de nacimiento si no hace falta.
- Una persona puede tener una sola postulación por necesidad. Retirarla libera el cupo solo si aún no hay compromiso confirmado.
- La postulación conserva también el estado del compromiso; el registro de horas y verificación se guarda aparte para mantener su ciclo de seguimiento.
- El servidor bloquea la confirmación de cupos que ya se agotaron, aun con postulaciones concurrentes.
- Las postulaciones y datos de contacto solo son visibles a la persona y a coordinadores de la organización responsable.
- Una actividad de riesgo alto o regulada no se publica en el MVP. La categoría y las condiciones deben validarse antes de publicar.
- El feedback no alimenta una clasificación pública. La compatibilidad informa; un responsable humano decide.

## Requisitos no funcionales

- Interfaz adaptable, navegable con teclado, mensajes comprensibles y contraste suficiente; aspirar a WCAG 2.2 AA.
- Autorización en el backend, contraseñas con hash seguro, cookies `HttpOnly`, protección CSRF, validación de entradas y límites de tamaño/tipo de adjuntos.
- Minimización de datos; consentimiento versionado; acceso privado a evidencias y feedback; secretos fuera del repositorio.
- Migraciones reproducibles en base vacía; índices y restricciones declarados en PostgreSQL; Hibernate en modo `validate`.
- API documentada y versionada; errores previsibles; registros operativos sin contraseñas, tokens ni contenido privado.

## Indicadores de validación

- Cobertura: porcentaje de necesidades que recibe una asignación confirmada.
- Cumplimiento: porcentaje de compromisos confirmados que termina en acción realizada.
- Tiempo de asignación desde publicación hasta confirmación.
- Compatibilidad valorada por el coordinador al finalizar.
- Reparticipación de personas que completan una segunda actividad.
- Tiempo de coordinación invertido por la organización antes y durante el piloto.

La investigación contempla conversar con 5–8 organizaciones, 12–20 personas voluntarias, observar tres convocatorias reales y completar el ciclo de al menos diez necesidades. Estas cifras son metas académicas ajustables, no resultados asumidos.

## Fuera del MVP

Pagos, manejo de dinero, rescate de alimentos perecederos, cadena de custodia, certificados tributarios, actividades médicas o reguladas, población menor de edad, gamificación, ranking público, despliegue nacional, logística compleja de inventarios y voluntariado corporativo.
