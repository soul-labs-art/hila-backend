package co.hila.api.volunteer;

import static co.hila.api.shared.MvpStore.view;

import co.hila.api.shared.*;
import co.hila.api.shared.MvpRequests.*;
import java.util.*;
import java.util.function.Function;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CapacityService {
  private final Database db;

  public CapacityService(Database db) {
    this.db = db;
  }

  private Map<String, Object> requireProfile(UUID user, boolean lock) {
    var rows =
        db.jdbc.queryForList(
            "SELECT user_id FROM volunteer_profiles WHERE user_id=?" + (lock ? " FOR UPDATE" : ""),
            user);
    if (rows.isEmpty())
      throw new ApiFailure(404, "PROFILE_NOT_FOUND", "Completa tu perfil para continuar.");
    return rows.get(0);
  }

  public List<Map<String, Object>> getMySkills(UUID user) {
    requireProfile(user, false);
    return db
        .jdbc
        .queryForList(
            """
            SELECT s.*, v.proficiency, v.years_experience
            FROM volunteer_skills v JOIN skills s ON s.id=v.skill_id
            WHERE v.volunteer_id=? ORDER BY s.name,s.id
            """,
            user)
        .stream()
        .map(
            row ->
                view(
                    "skill",
                    MvpStore.fields(row, "id", "slug", "name", "category", "active"),
                    "proficiency",
                    row.get("proficiency"),
                    "yearsExperience",
                    row.get("years_experience")))
        .toList();
  }

  @Transactional
  public List<Map<String, Object>> putMySkills(UUID user, SkillsUpdate body) {
    requireProfile(user, true);
    replaceReferences(
        user, body.items(), VolunteerSkillInput::skillId, "volunteer_skills", "skills", "skill_id");
    for (var skill : body.items()) {
      db.jdbc.update(
          "INSERT INTO volunteer_skills(volunteer_id,skill_id,proficiency,years_experience) VALUES"
              + " (?,?,?,?)",
          user,
          skill.skillId(),
          skill.proficiency(),
          skill.yearsExperience());
    }
    return getMySkills(user);
  }

  public Map<String, Object> getMyAvailability(UUID user) {
    requireProfile(user, false);
    var items =
        db.jdbc.queryForList(
            """
            SELECT weekday,to_char(starts_at,'HH24:MI') AS "startsAt", to_char(ends_at,'HH24:MI') AS "endsAt"
            FROM volunteer_availability WHERE volunteer_id=? ORDER BY weekday,starts_at
            """,
            user);
    return view("timezone", "America/Bogota", "items", items);
  }

  @Transactional
  public Map<String, Object> putMyAvailability(UUID user, Availability body) {
    requireProfile(user, true);
    var sorted =
        body.items().stream()
            .sorted(
                Comparator.comparing(AvailabilitySlot::weekday)
                    .thenComparing(AvailabilitySlot::startsAt))
            .toList();
    AvailabilitySlot previous = null;
    for (var slot : sorted) {
      if (slot.startsAt().compareTo(slot.endsAt()) >= 0)
        throw ApiFailure.invalid("La franja debe terminar después de comenzar.");
      if (previous != null
          && previous.weekday().equals(slot.weekday())
          && previous.endsAt().compareTo(slot.startsAt()) > 0)
        throw ApiFailure.invalid("Las franjas no pueden solaparse.");
      previous = slot;
    }
    db.jdbc.update("DELETE FROM volunteer_availability WHERE volunteer_id=?", user);
    for (var slot : sorted) {
      db.jdbc.update(
          "INSERT INTO volunteer_availability(volunteer_id,weekday,starts_at,ends_at) VALUES"
              + " (?,?,?::time,?::time)",
          user,
          slot.weekday(),
          slot.startsAt(),
          slot.endsAt());
    }
    return getMyAvailability(user);
  }

  public List<Map<String, Object>> getMyServiceAreas(UUID user) {
    requireProfile(user, false);
    return db.jdbc.queryForList(
        """
        SELECT t.id,t.slug,t.name,t.territory_type AS type,t.parent_id AS "parentId",t.active
        FROM volunteer_service_areas v JOIN territories t ON t.id=v.territory_id
        WHERE v.volunteer_id=? ORDER BY t.name,t.id
        """,
        user);
  }

  @Transactional
  public List<Map<String, Object>> putMyServiceAreas(UUID user, ServiceAreasUpdate body) {
    requireProfile(user, true);
    replaceReferences(
        user,
        body.territoryIds(),
        Function.identity(),
        "volunteer_service_areas",
        "territories",
        "territory_id");
    for (UUID id : body.territoryIds()) {
      db.jdbc.update(
          "INSERT INTO volunteer_service_areas(volunteer_id,territory_id) VALUES (?,?)", user, id);
    }
    return getMyServiceAreas(user);
  }

  public List<Map<String, Object>> getMyResources(UUID user) {
    requireProfile(user, false);
    return db
        .jdbc
        .queryForList(
            """
            SELECT r.*,v.details FROM volunteer_resources v JOIN resources r ON r.id=v.resource_id
            WHERE v.volunteer_id=? ORDER BY r.name,r.id
            """,
            user)
        .stream()
        .map(
            row ->
                view(
                    "resource",
                    MvpStore.fields(row, "id", "slug", "name", "category", "active"),
                    "details",
                    row.get("details")))
        .toList();
  }

  @Transactional
  public List<Map<String, Object>> putMyResources(UUID user, ResourcesUpdate body) {
    requireProfile(user, true);
    replaceReferences(
        user,
        body.items(),
        VolunteerResourceInput::resourceId,
        "volunteer_resources",
        "resources",
        "resource_id");
    for (var item : body.items()) {
      db.jdbc.update(
          "INSERT INTO volunteer_resources(volunteer_id,resource_id,details) VALUES (?,?,?)",
          user,
          item.resourceId(),
          MvpStore.text(item.details()));
    }
    return getMyResources(user);
  }

  private <T> void replaceReferences(
      UUID user, List<T> items, Function<T, UUID> id, String link, String catalog, String column) {
    Set<UUID> seen = new HashSet<>();
    for (T item : items) {
      UUID reference = id.apply(item);
      if (!seen.add(reference)) throw ApiFailure.invalid("No repitas entradas en la colección.");
      var rows =
          db.jdbc.queryForList(
              "SELECT active FROM " + catalog + " WHERE id=? FOR SHARE", Boolean.class, reference);
      if (rows.isEmpty()) throw ApiFailure.invalid("La referencia no existe.");
      if (!rows.get(0)
          && db.jdbc
              .queryForList(
                  "SELECT 1 FROM " + link + " WHERE volunteer_id=? AND " + column + "=?",
                  user,
                  reference)
              .isEmpty()) throw ApiFailure.invalid("La referencia está inactiva.");
    }
    db.jdbc.update("DELETE FROM " + link + " WHERE volunteer_id=?", user);
  }
}
