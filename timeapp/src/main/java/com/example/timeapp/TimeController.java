package com.example.timeapp;

import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TimeController {

    private final JdbcTemplate jdbcTemplate;

    public TimeController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @GetMapping(value = "/", produces = MediaType.TEXT_HTML_VALUE)
    public String now() {
        Map<String, Object> row = jdbcTemplate.queryForMap("""
                SELECT
                  to_char(now() AT TIME ZONE 'Asia/Seoul', 'YYYY-MM-DD HH24:MI:SS') AS seoul_time,
                  to_char(now() AT TIME ZONE 'UTC',        'YYYY-MM-DD HH24:MI:SS') AS utc_time,
                  version()                                                         AS db_version
                """);

        return """
                <!doctype html>
                <html lang="ko">
                <head>
                  <meta charset="utf-8">
                  <title>DB 현재 시간</title>
                  <style>
                    body { font-family: sans-serif; max-width: 640px; margin: 60px auto; padding: 0 16px; }
                    .time { font-size: 2rem; font-weight: bold; margin: 8px 0 24px; }
                    .muted { color: #666; font-size: 0.9rem; }
                  </style>
                </head>
                <body>
                  <h1>PostgreSQL 현재 시간</h1>
                  <p>서울 (KST)</p>
                  <div class="time">%s</div>
                  <p>UTC</p>
                  <div class="time">%s</div>
                  <p class="muted">%s</p>
                  <p class="muted">접속정보 출처: AWS SSM Parameter Store</p>
                </body>
                </html>
                """.formatted(row.get("seoul_time"), row.get("utc_time"), row.get("db_version"));
    }
}