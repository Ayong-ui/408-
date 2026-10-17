package com.xiuxian.common;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 洞府设置：键值存在 setting 表里，没存过的用默认值。 */
@Service
public class SettingsService {

    private static final Map<String, Object> DEFAULTS = defaults();
    private static final Set<String> EFFECT_LEVELS = Set.of("full", "light", "none");

    private final JdbcTemplate jdbc;
    private volatile Map<String, Object> cache;

    public SettingsService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static Map<String, Object> defaults() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("daoName", "无名道友");
        m.put("dayStartHour", 4);       // 日界线：凌晨 4 点前算前一天
        m.put("incenseMinutes", 45);    // 一炷香的长度
        m.put("effectLevel", "full");   // 特效强度 full | light | none
        m.put("copyEnabled", true);     // 随机修仙文案
        m.put("plainMode", false);      // 白话模式
        m.put("soundEnabled", false);   // 钟磬音效
        m.put("idleAskMinutes", 180);   // 入定多久没动静就问一句"还在否"
        return Collections.unmodifiableMap(m);
    }

    public Map<String, Object> all() {
        Map<String, Object> c = cache;
        if (c == null) {
            c = load();
            cache = c;
        }
        return c;
    }

    public int getInt(String key) {
        return (Integer) all().get(key);
    }

    public boolean getBool(String key) {
        return (Boolean) all().get(key);
    }

    public String getString(String key) {
        return (String) all().get(key);
    }

    /** 只更新传进来的键；未知的键或不合法的值直接报错。 */
    @Transactional
    public synchronized Map<String, Object> update(Map<String, Object> patch) {
        Map<String, Object> clean = new LinkedHashMap<>();
        patch.forEach((k, v) -> clean.put(k, validate(k, v)));
        clean.forEach((k, v) -> jdbc.update(
                "INSERT INTO setting(name, value) VALUES (?, ?) ON CONFLICT(name) DO UPDATE SET value = excluded.value",
                k, String.valueOf(v)));
        cache = null;
        return all();
    }

    private Map<String, Object> load() {
        Map<String, Object> m = new LinkedHashMap<>(DEFAULTS);
        jdbc.query("SELECT name, value FROM setting", rs -> {
            String key = rs.getString(1);
            if (DEFAULTS.containsKey(key)) {
                m.put(key, parse(key, rs.getString(2)));
            }
        });
        return Collections.unmodifiableMap(m);
    }

    private static Object parse(String key, String raw) {
        Object def = DEFAULTS.get(key);
        if (def instanceof Integer) {
            try {
                return Integer.parseInt(raw);
            } catch (NumberFormatException e) {
                return def;
            }
        }
        if (def instanceof Boolean) {
            return Boolean.parseBoolean(raw);
        }
        return raw;
    }

    private static Object validate(String key, Object value) {
        if (!DEFAULTS.containsKey(key)) {
            throw ApiException.badRequest("未知的设置项：" + key);
        }
        return switch (key) {
            case "daoName" -> {
                String s = value == null ? "" : value.toString().trim();
                if (s.isEmpty() || s.length() > 12) {
                    throw ApiException.badRequest("道号需为 1–12 个字");
                }
                yield s;
            }
            case "dayStartHour" -> intIn(value, 0, 12, "日界线需在 0–12 点之间");
            case "incenseMinutes" -> intIn(value, 5, 180, "一炷香需在 5–180 分钟之间");
            case "idleAskMinutes" -> intIn(value, 30, 720, "忘关提醒需在 30–720 分钟之间");
            case "effectLevel" -> {
                if (value == null || !EFFECT_LEVELS.contains(value.toString())) {
                    throw ApiException.badRequest("特效强度只能是 full / light / none");
                }
                yield value.toString();
            }
            default -> {
                if (!(value instanceof Boolean b)) {
                    throw ApiException.badRequest(key + " 需为 true 或 false");
                }
                yield b;
            }
        };
    }

    private static int intIn(Object value, int min, int max, String message) {
        if (!(value instanceof Number n) || n.doubleValue() != Math.rint(n.doubleValue())) {
            throw ApiException.badRequest(message);
        }
        int v = n.intValue();
        if (v < min || v > max) {
            throw ApiException.badRequest(message);
        }
        return v;
    }
}
