package org.openelisglobal.audittrail.access;

import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Lists the patient access log ("who viewed what") for the system audit trail
 * screen. Filtering by patient also finds reads that named one of the patient's
 * lab numbers rather than the patient.
 */
@RestController
@PreAuthorize("hasRole('ADMIN')")
public class PatientAccessLogRestController {

    private static final int MAX_EXPORT_ROWS = 10_000;

    private static final String SELECT = "SELECT l.id, l.access_time, l.sys_user_id, l.login_name, l.http_method,"
            + " l.resource, l.query_string, l.patient_id, l.accession_number, l.client_address"
            + " FROM clinlims.patient_access_log l";

    @Autowired
    private DataSource dataSource;

    private NamedParameterJdbcTemplate jdbc;

    @PostConstruct
    private void init() {
        jdbc = new NamedParameterJdbcTemplate(dataSource);
    }

    @GetMapping("/rest/patientAccessLog")
    public ResponseEntity<Map<String, Object>> getAccessLog(@RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate, @RequestParam(required = false) String userId,
            @RequestParam(required = false) String patientId, @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "30") int pageSize) {
        int safePage = Math.max(1, page);
        int safePageSize = Math.max(1, Math.min(pageSize, 100));

        MapSqlParameterSource params = new MapSqlParameterSource();
        String where = where(startDate, endDate, userId, patientId, search, params);

        Long total = jdbc.queryForObject("SELECT count(*) FROM clinlims.patient_access_log l" + where, params,
                Long.class);
        params.addValue("limit", safePageSize).addValue("offset", (safePage - 1) * safePageSize);
        List<Map<String, Object>> rows = query(
                where + " ORDER BY l.access_time DESC, l.id DESC" + " LIMIT :limit OFFSET :offset", params);

        long totalItems = total == null ? 0 : total;
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("events", rows);
        response.put("page", safePage);
        response.put("pageSize", safePageSize);
        response.put("totalItems", totalItems);
        response.put("totalPages", (int) Math.ceil((double) totalItems / safePageSize));
        return ResponseEntity.ok(response);
    }

    @GetMapping("/rest/patientAccessLog/export")
    public void exportCsv(@RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate, @RequestParam(required = false) String userId,
            @RequestParam(required = false) String patientId, @RequestParam(required = false) String search,
            HttpServletResponse response) throws IOException {
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("limit", MAX_EXPORT_ROWS);
        String where = where(startDate, endDate, userId, patientId, search, params);
        List<Map<String, Object>> rows = query(where + " ORDER BY l.access_time DESC, l.id DESC LIMIT :limit", params);

        response.setContentType("text/csv; charset=UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename=\"patient-access-log.csv\"");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        PrintWriter out = response.getWriter();
        out.println("Time,User,User ID,Method,Resource,Patient ID,Lab number,Query,Client address");
        for (Map<String, Object> row : rows) {
            out.println(String.join(",", csv(row.get("timestamp")), csv(row.get("user")), csv(row.get("userId")),
                    csv(row.get("method")), csv(row.get("resource")), csv(row.get("patientId")),
                    csv(row.get("accessionNumber")), csv(row.get("query")), csv(row.get("clientAddress"))));
        }
        out.flush();
    }

    private List<Map<String, Object>> query(String tail, MapSqlParameterSource params) {
        return jdbc.query(SELECT + tail, params, (rs, i) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", rs.getLong("id"));
            Timestamp time = rs.getTimestamp("access_time");
            row.put("timestamp", time == null ? null : time.toInstant().toString());
            row.put("userId", rs.getString("sys_user_id"));
            row.put("user", rs.getString("login_name"));
            row.put("method", rs.getString("http_method"));
            row.put("resource", rs.getString("resource"));
            row.put("query", rs.getString("query_string"));
            row.put("patientId", rs.getString("patient_id"));
            row.put("accessionNumber", rs.getString("accession_number"));
            row.put("clientAddress", rs.getString("client_address"));
            return row;
        });
    }

    static String where(String startDate, String endDate, String userId, String patientId, String search,
            MapSqlParameterSource params) {
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        LocalDate start = parseDate(startDate);
        if (start != null) {
            where.append(" AND l.access_time >= :start");
            params.addValue("start", Timestamp.valueOf(start.atStartOfDay()));
        }
        LocalDate end = parseDate(endDate);
        if (end != null) {
            where.append(" AND l.access_time < :end");
            params.addValue("end", Timestamp.valueOf(end.plusDays(1).atStartOfDay()));
        }
        if (isId(userId)) {
            where.append(" AND l.sys_user_id = :userId");
            params.addValue("userId", Integer.valueOf(userId.trim()));
        }
        if (isId(patientId)) {
            where.append(" AND (l.patient_id = :patientId OR l.accession_number IN (SELECT s.accession_number"
                    + " FROM clinlims.sample s JOIN clinlims.sample_human sh ON sh.samp_id = s.id"
                    + " WHERE sh.patient_id = :patientId))");
            params.addValue("patientId", Integer.valueOf(patientId.trim()));
        }
        if (search != null && !search.isBlank()) {
            where.append(" AND (l.login_name ILIKE :search OR l.resource ILIKE :search"
                    + " OR l.query_string ILIKE :search OR l.accession_number ILIKE :search)");
            params.addValue("search",
                    "%" + search.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
        }
        return where.toString();
    }

    private static boolean isId(String value) {
        return value != null && value.trim().matches("\\d{1,9}");
    }

    private static LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** Quotes a CSV field and neutralises spreadsheet formulas. */
    static String csv(Object value) {
        if (value == null) {
            return "";
        }
        String s = String.valueOf(value);
        if (!s.isEmpty() && "=+-@\t\r".indexOf(s.charAt(0)) >= 0) {
            s = "'" + s;
        }
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }
}
