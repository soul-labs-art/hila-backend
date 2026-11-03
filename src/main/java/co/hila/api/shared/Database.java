package co.hila.api.shared;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class Database {
  public final JdbcTemplate jdbc;

  public Database(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public Map<String, Object> one(String sql, Object... args) {
    var list = jdbc.queryForList(sql, args);
    if (list.isEmpty()) throw ApiFailure.missing();
    return list.get(0);
  }

  public UUID id(Map<String, Object> row, String key) {
    return (UUID) row.get(key);
  }

  public void audit(UUID actor, String action, String resource, UUID id) {
    jdbc.update(
        "INSERT INTO audit_events(actor_user_id,action,resource_type,resource_id) VALUES (?,?,?,?)",
        actor,
        action,
        resource,
        id);
  }
}
