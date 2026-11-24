package co.hila.api.opportunities;

import static co.hila.api.shared.MvpStore.view;

import co.hila.api.shared.Database;
import java.time.*;
import java.util.*;
import java.util.function.Predicate;
import org.springframework.stereotype.Service;

@Service
public class CompatibilityService {
  private static final ZoneId BOGOTA = ZoneId.of("America/Bogota");
  private static final List<String> PROFICIENCY = List.of("BASIC", "INTERMEDIATE", "ADVANCED");
  private final Database db;

  public CompatibilityService(Database db) {
    this.db = db;
  }

  boolean basic(UUID user, Map<String, Object> need) {
    return !mode(user, need).get("status").equals("MISSING")
        && !territory(user, need).get("status").equals("MISSING");
  }

  Map<String, Object> evaluate(
      UUID user,
      Map<String, Object> need,
      List<Map<String, Object>> skills,
      List<Map<String, Object>> resources) {
    var declaredSkills =
        db.jdbc.queryForList(
            "SELECT skill_id,proficiency FROM volunteer_skills WHERE volunteer_id=?", user);
    var declaredResources =
        new HashSet<>(
            db.jdbc.queryForList(
                "SELECT resource_id FROM volunteer_resources WHERE volunteer_id=?",
                UUID.class,
                user));
    var criteria =
        List.of(
            catalog(
                "SKILLS",
                "skill",
                skills,
                skill ->
                    declaredSkills.stream()
                        .anyMatch(
                            declared ->
                                id(skill, "skill").equals(declared.get("skill_id"))
                                    && (skill.get("minimumProficiency") == null
                                        || PROFICIENCY.indexOf(
                                                Objects.toString(declared.get("proficiency"), ""))
                                            >= PROFICIENCY.indexOf(
                                                skill.get("minimumProficiency"))))),
            schedule(user, need),
            mode(user, need),
            territory(user, need),
            catalog(
                "RESOURCES",
                "resource",
                resources,
                resource -> declaredResources.contains(id(resource, "resource"))));
    return view(
        "needId", need.get("id"), "criteria", criteria, "evaluatedAt", Instant.now().toString());
  }

  private UUID id(Map<String, Object> item, String kind) {
    return (UUID) ((Map<?, ?>) item.get(kind)).get("id");
  }

  private Map<String, Object> catalog(
      String criterion,
      String kind,
      List<Map<String, Object>> entries,
      Predicate<Map<String, Object>> matches) {
    var matched = new ArrayList<UUID>();
    var missing = new ArrayList<UUID>();
    for (var entry : entries) {
      (matches.test(entry) ? matched : missing).add(id(entry, kind));
    }
    boolean required = entries.stream().anyMatch(e -> Boolean.TRUE.equals(e.get("required")));
    if (entries.isEmpty())
      return criterion(
          criterion, "NOT_APPLICABLE", false, "La actividad no pide estos requisitos.");
    String status = missing.isEmpty() ? "MATCH" : matched.isEmpty() ? "MISSING" : "PARTIAL";
    String reason =
        switch (status) {
          case "MATCH" -> "Tu perfil coincide con todos los requisitos.";
          case "PARTIAL" -> "Tu perfil coincide con algunos de los requisitos.";
          default -> "Tu perfil no coincide con estos requisitos.";
        };
    return criterion(criterion, status, required, reason, matched, missing);
  }

  private Map<String, Object> mode(UUID user, Map<String, Object> need) {
    Object preferred =
        db.one("SELECT preferred_modality FROM volunteer_profiles WHERE user_id=?", user)
            .get("preferred_modality");
    Object actual = need.get("modality");
    if (preferred == null)
      return criterion("MODALITY", "MISSING", true, "Aún no indicas tu modalidad preferida.");
    if (preferred.equals(actual) || preferred.equals("HYBRID"))
      return criterion("MODALITY", "MATCH", true, "Tu modalidad es compatible.");
    if ("HYBRID".equals(actual))
      return criterion("MODALITY", "PARTIAL", true, "Podrías cubrir parte de la actividad mixta.");
    return criterion("MODALITY", "MISSING", true, "La modalidad es distinta a la de tu perfil.");
  }

  private Set<UUID> lineage(UUID id) {
    return new HashSet<>(
        db.jdbc.queryForList(
            """
            WITH RECURSIVE ancestors AS (
              SELECT id,parent_id FROM territories WHERE id=?
              UNION SELECT t.id,t.parent_id FROM territories t JOIN ancestors a ON a.parent_id=t.id
            ) SELECT id FROM ancestors
            """,
            UUID.class,
            id));
  }

  private Map<String, Object> territory(UUID user, Map<String, Object> need) {
    UUID target = (UUID) need.get("territory_id");
    if (target == null || "REMOTE".equals(need.get("modality")))
      return criterion(
          "TERRITORY", "NOT_APPLICABLE", false, "El territorio no aplica a la actividad remota.");
    var areas =
        db.jdbc.queryForList(
            "SELECT territory_id FROM volunteer_service_areas WHERE volunteer_id=?",
            UUID.class,
            user);
    var ancestors = lineage(target);
    if (areas.stream().anyMatch(ancestors::contains))
      return criterion(
          "TERRITORY",
          "MATCH",
          true,
          "La actividad queda en una de tus zonas.",
          List.of(target),
          List.of());
    if (areas.stream().anyMatch(area -> lineage(area).contains(target)))
      return criterion(
          "TERRITORY",
          "PARTIAL",
          true,
          "Cubres parte del territorio de la actividad.",
          List.of(),
          List.of(target));
    return criterion(
        "TERRITORY",
        "MISSING",
        true,
        "La actividad queda fuera de tus zonas declaradas.",
        List.of(),
        List.of(target));
  }

  private Map<String, Object> schedule(UUID user, Map<String, Object> need) {
    var hours =
        db.jdbc.queryForList(
            """
            SELECT weekday,starts_at,ends_at FROM volunteer_availability
            WHERE volunteer_id=? ORDER BY weekday,starts_at
            """,
            user);
    ZonedDateTime cursor = OpportunityService.stamp(need, "starts_at").atZone(BOGOTA);
    ZonedDateTime end = OpportunityService.stamp(need, "ends_at").atZone(BOGOTA);
    boolean all = true;
    boolean some = false;
    int wholeDays = 0;
    while (cursor.isBefore(end)) {
      ZonedDateTime midnight = cursor.toLocalDate().plusDays(1).atStartOfDay(BOGOTA);
      ZonedDateTime boundary = end.isBefore(midnight) ? end : midnight;
      if (cursor.toLocalTime().equals(LocalTime.MIDNIGHT)
          && boundary.equals(midnight)
          && ++wholeDays > 7) {
        cursor = end.toLocalDate().atStartOfDay(BOGOTA);
        continue;
      }
      long from = cursor.toLocalTime().toNanoOfDay();
      long until =
          boundary.equals(midnight)
              ? Duration.ofDays(1).toNanos()
              : boundary.toLocalTime().toNanoOfDay();
      long covered = from;
      for (var slot : hours) {
        if (((Number) slot.get("weekday")).intValue() != cursor.getDayOfWeek().getValue()) continue;
        long start = ((java.sql.Time) slot.get("starts_at")).toLocalTime().toNanoOfDay();
        long finish = ((java.sql.Time) slot.get("ends_at")).toLocalTime().toNanoOfDay();
        if (start < until && finish > from) some = true;
        if (start <= covered && finish > covered) covered = finish;
      }
      all &= covered >= until;
      cursor = boundary;
    }
    String status = all ? "MATCH" : some ? "PARTIAL" : "MISSING";
    return criterion(
        "AVAILABILITY",
        status,
        true,
        all
            ? "Tu disponibilidad cubre todo el horario."
            : some
                ? "Tu disponibilidad cubre una parte del horario."
                : "Tu disponibilidad no coincide con el horario.");
  }

  private Map<String, Object> criterion(
      String kind, String status, boolean required, String reason) {
    return criterion(kind, status, required, reason, List.of(), List.of());
  }

  private Map<String, Object> criterion(
      String kind,
      String status,
      boolean required,
      String reason,
      List<UUID> matched,
      List<UUID> missing) {
    return view(
        "criterion",
        kind,
        "status",
        status,
        "required",
        required,
        "reason",
        reason,
        "matchedIds",
        matched,
        "missingIds",
        missing);
  }
}
