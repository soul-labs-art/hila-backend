package co.hila.api.shared;

import java.time.OffsetDateTime;
import java.util.*;
import java.util.function.Function;
import org.springframework.stereotype.Component;

@Component
public class MvpStore {
  public final Database db;

  public MvpStore(Database db) {
    this.db = db;
  }

  public static Map<String, Object> view(Object... pairs) {
    Map<String, Object> result = new LinkedHashMap<>();
    for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], pairs[i + 1]);
    return result;
  }

  public static Map<String, Object> fields(Map<String, Object> row, String... names) {
    Map<String, Object> result = new LinkedHashMap<>();
    for (String name : names) {
      String column = name.replaceAll("([A-Z])", "_$1").toLowerCase(Locale.ROOT);
      result.put(name, row.get(column));
    }
    return result;
  }

  public static String text(String value) {
    return value == null ? null : value.trim();
  }

  public static ApiFailure conflict(String code, String message) {
    return new ApiFailure(409, code, message);
  }

  public static ApiFailure transition(String message) {
    return conflict("INVALID_TRANSITION", message);
  }

  public static ApiFailure forbidden() {
    return new ApiFailure(403, "FORBIDDEN", "No tienes permiso para esta acción.");
  }

  public static void filterValue(String value, String... allowed) {
    if (value != null && !List.of(allowed).contains(value)) {
      throw new ApiFailure(400, "MALFORMED_REQUEST", "El valor del filtro no es válido.");
    }
  }

  public static void order(OffsetDateTime from, OffsetDateTime to) {
    if (from != null && to != null && from.isAfter(to)) {
      throw ApiFailure.invalidField(
          "to", "OUT_OF_RANGE", "El inicio del intervalo debe preceder a su fin.");
    }
  }

  public long count(String sql, Object... args) {
    return db.jdbc.queryForObject(sql, Long.class, args);
  }

  public Map<String, Object> page(
      Filter filter,
      String order,
      int page,
      int size,
      Function<Map<String, Object>, Map<String, Object>> mapper) {
    if (page < 0 || size < 1 || size > 100) throw ApiFailure.invalid("Paginación fuera de rango.");
    var values = new ArrayList<>(filter.args);
    long count = count("SELECT count(*) FROM (" + filter.sql + ") AS matched", values.toArray());
    values.add(size);
    values.add((long) page * size);
    var items =
        db
            .jdbc
            .queryForList(filter.sql + " ORDER BY " + order + " LIMIT ? OFFSET ?", values.toArray())
            .stream()
            .map(mapper)
            .toList();
    return view(
        "items",
        items,
        "page",
        page,
        "size",
        size,
        "totalElements",
        count,
        "totalPages",
        (count + size - 1) / size);
  }

  public static final class Filter {
    private final StringBuilder sql;
    private final List<Object> args = new ArrayList<>();

    public Filter(String base) {
      sql = new StringBuilder(base).append(" WHERE true");
    }

    public Filter add(String condition, Object... values) {
      sql.append(" AND (").append(condition).append(')');
      Collections.addAll(args, values);
      return this;
    }

    public Filter when(String condition, Object value) {
      if (value != null) add(condition, value);
      return this;
    }

    public Filter search(String q, String... columns) {
      if (q == null || q.isBlank()) return this;
      if (q.length() > 140)
        throw new ApiFailure(400, "MALFORMED_REQUEST", "El filtro q supera 140 caracteres.");
      String value = q.trim().toLowerCase(Locale.ROOT);
      String condition =
          String.join(
              " OR ", Arrays.stream(columns).map(c -> "strpos(lower(" + c + "), ?) > 0").toList());
      return add(condition, Collections.nCopies(columns.length, value).toArray());
    }
  }
}
