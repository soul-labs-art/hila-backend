package co.hila.api.admin;

import static co.hila.api.shared.MvpStore.*;

import co.hila.api.profiles.ProfileService;
import co.hila.api.shared.*;
import co.hila.api.shared.MvpRequests.*;
import java.util.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CatalogService {
  private enum Catalog {
    SKILL("skills"),
    RESOURCE("resources"),
    TERRITORY("territories");
    final String table;

    Catalog(String table) {
      this.table = table;
    }
  }

  private static final List<String> LEVELS =
      List.of("VALLEY", "MUNICIPALITY", "COMMUNE", "NEIGHBORHOOD");
  private final MvpStore store;
  private final Database db;
  private final ProfileService profiles;

  public CatalogService(MvpStore store, ProfileService profiles) {
    this.store = store;
    this.db = store.db;
    this.profiles = profiles;
  }

  public Map<String, Object> listSkillCatalog(String q, String category, int page, int size) {
    return list(Catalog.SKILL, true, q, category, page, size);
  }

  public Map<String, Object> listResourceCatalog(String q, String category, int page, int size) {
    return list(Catalog.RESOURCE, true, q, category, page, size);
  }

  public Map<String, Object> adminListSkill(
      UUID actor, Boolean active, String q, int page, int size) {
    profiles.admin(actor);
    return list(Catalog.SKILL, active, q, null, page, size);
  }

  public Map<String, Object> adminListResource(
      UUID actor, Boolean active, String q, int page, int size) {
    profiles.admin(actor);
    return list(Catalog.RESOURCE, active, q, null, page, size);
  }

  public Map<String, Object> adminListTerritory(
      UUID actor, Boolean active, String q, int page, int size) {
    profiles.admin(actor);
    return list(Catalog.TERRITORY, active, q, null, page, size);
  }

  @Transactional
  public Map<String, Object> adminCreateSkill(UUID actor, SkillWrite b) {
    return write(actor, Catalog.SKILL, null, b.slug(), b.name(), b.category(), null, b.active());
  }

  @Transactional
  public Map<String, Object> adminUpdateSkill(UUID actor, UUID entry, SkillWrite b) {
    return write(actor, Catalog.SKILL, entry, b.slug(), b.name(), b.category(), null, b.active());
  }

  @Transactional
  public Map<String, Object> adminCreateResource(UUID actor, ResourceWrite b) {
    return write(actor, Catalog.RESOURCE, null, b.slug(), b.name(), b.category(), null, b.active());
  }

  @Transactional
  public Map<String, Object> adminUpdateResource(UUID actor, UUID entry, ResourceWrite b) {
    return write(
        actor, Catalog.RESOURCE, entry, b.slug(), b.name(), b.category(), null, b.active());
  }

  @Transactional
  public Map<String, Object> adminCreateTerritory(UUID actor, TerritoryWrite b) {
    return write(
        actor, Catalog.TERRITORY, null, b.slug(), b.name(), b.type(), b.parentId(), b.active());
  }

  @Transactional
  public Map<String, Object> adminUpdateTerritory(UUID actor, UUID entry, TerritoryWrite b) {
    return write(
        actor, Catalog.TERRITORY, entry, b.slug(), b.name(), b.type(), b.parentId(), b.active());
  }

  private Map<String, Object> list(
      Catalog catalog, Boolean active, String q, String category, int page, int size) {
    var query =
        new Filter("SELECT * FROM " + catalog.table)
            .when("active=?", active)
            .when("category=?", text(category))
            .search(q, "name");
    return store.page(query, "name,id", page, size, row -> entry(catalog, row));
  }

  private Map<String, Object> entry(Catalog catalog, Map<String, Object> row) {
    if (catalog == Catalog.TERRITORY) {
      var value = fields(row, "id", "slug", "name", "parentId", "active");
      value.put("type", row.get("territory_type"));
      return value;
    }
    return fields(row, "id", "slug", "name", "category", "active");
  }

  private Map<String, Object> write(
      UUID actor,
      Catalog catalog,
      UUID previousId,
      String slug,
      String name,
      String categoryOrType,
      UUID parent,
      boolean active) {
    profiles.admin(actor);
    UUID id = previousId == null ? UUID.randomUUID() : previousId;
    boolean territory = catalog == Catalog.TERRITORY;
    if (territory) checkHierarchy(id, categoryOrType, parent, active);
    try {
      if (previousId == null) {
        String fields = territory ? "territory_type,parent_id,active" : "category,active";
        String placeholders = territory ? "?,?,?,?,?,?" : "?,?,?,?,?";
        Object[] params =
            territory
                ? new Object[] {id, slug, text(name), categoryOrType, parent, active}
                : new Object[] {id, slug, text(name), text(categoryOrType), active};
        db.jdbc.update(
            "INSERT INTO "
                + catalog.table
                + "(id,slug,name,"
                + fields
                + ") VALUES ("
                + placeholders
                + ")",
            params);
      } else {
        String fields = territory ? "territory_type=?,parent_id=?,active=?" : "category=?,active=?";
        Object[] params =
            territory
                ? new Object[] {slug, text(name), categoryOrType, parent, active, id}
                : new Object[] {slug, text(name), text(categoryOrType), active, id};
        if (db.jdbc.update(
                "UPDATE " + catalog.table + " SET slug=?,name=?," + fields + " WHERE id=?", params)
            == 0) {
          throw ApiFailure.missing();
        }
      }
    } catch (DuplicateKeyException e) {
      throw conflict("CATALOG_ENTRY_EXISTS", "Ya existe una entrada con ese identificador.");
    }
    db.audit(
        actor,
        "CATALOG_" + catalog.name() + (previousId == null ? "_CREATED" : "_UPDATED"),
        catalog.name(),
        id);
    return entry(catalog, db.one("SELECT * FROM " + catalog.table + " WHERE id=?", id));
  }

  private void checkHierarchy(UUID id, String type, UUID parent, boolean active) {
    db.jdbc.execute("LOCK TABLE territories IN SHARE ROW EXCLUSIVE MODE");
    int level = LEVELS.indexOf(type);
    if ((level == 0) != (parent == null) || id.equals(parent)) {
      throw ApiFailure.invalid(
          "El territorio requiere un padre válido del nivel inmediatamente superior.");
    }
    if (parent != null) {
      var rows =
          db.jdbc.queryForList("SELECT territory_type,active FROM territories WHERE id=?", parent);
      if (rows.isEmpty()
          || !LEVELS.get(level - 1).equals(rows.get(0).get("territory_type"))
          || (active && !Boolean.TRUE.equals(rows.get(0).get("active")))) {
        throw ApiFailure.invalid("El territorio padre no es válido o está inactivo.");
      }
    }
    if (level < LEVELS.size() - 1) {
      if (store.count(
              "SELECT count(*) FROM territories WHERE parent_id=? AND territory_type<>?",
              id,
              LEVELS.get(level + 1))
          > 0)
        throw ApiFailure.invalid("El nuevo nivel es incompatible con los territorios hijos.");
    } else if (store.count("SELECT count(*) FROM territories WHERE parent_id=?", id) > 0) {
      throw ApiFailure.invalid("Este territorio no puede tener hijos.");
    }
  }
}
