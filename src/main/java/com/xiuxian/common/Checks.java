package com.xiuxian.common;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.regex.Pattern;

/** 入参校验：不合法直接抛 400。文本长度按字数（码点）算，首尾空白会被去掉。 */
public final class Checks {

    private static final Pattern COLOR = Pattern.compile("^#[0-9a-fA-F]{6}$");
    private static final Pattern HHMM = Pattern.compile("^([01]\\d|2[0-3]):[0-5]\\d$");

    private Checks() {
    }

    /** 必填文本，字数需在 [min, max]。 */
    public static String required(String value, String label, int min, int max) {
        String s = value == null ? "" : value.strip();
        int len = s.codePointCount(0, s.length());
        if (len < min || len > max) {
            throw ApiException.badRequest(label + "需为 " + min + "–" + max + " 个字");
        }
        return s;
    }

    /** 选填文本：空白视为 null。 */
    public static String optional(String value, String label, int max) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String s = value.strip();
        if (s.codePointCount(0, s.length()) > max) {
            throw ApiException.badRequest(label + "不能超过 " + max + " 个字");
        }
        return s;
    }

    public static String color(String value) {
        if (value == null || !COLOR.matcher(value.strip()).matches()) {
            throw ApiException.badRequest("颜色需为 #rrggbb 格式");
        }
        return value.strip().toLowerCase();
    }

    public static String hhmm(String value, String label) {
        if (value == null || !HHMM.matcher(value.strip()).matches()) {
            throw ApiException.badRequest(label + "需为 HH:mm 格式");
        }
        return value.strip();
    }

    public static LocalDate day(String value) {
        try {
            return LocalDate.parse(value == null ? "" : value.strip());
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest("日期需为 yyyy-MM-dd 格式");
        }
    }

    /** 网址：必填时不能为空；非空时必须以 http:// 或 https:// 开头。 */
    public static String url(String value, boolean required, int max) {
        if (value == null || value.isBlank()) {
            if (required) {
                throw ApiException.badRequest("网址不能为空");
            }
            return null;
        }
        String s = value.strip();
        String lower = s.toLowerCase();
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            throw ApiException.badRequest("网址需以 http:// 或 https:// 开头");
        }
        if (s.length() > max) {
            throw ApiException.badRequest("网址太长");
        }
        return s;
    }

    public static boolean bool(Boolean value, boolean defaultValue) {
        return value == null ? defaultValue : value;
    }
}
