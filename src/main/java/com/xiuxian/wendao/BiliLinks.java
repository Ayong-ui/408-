package com.xiuxian.wendao;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.xiuxian.common.ApiException;

/**
 * 从用户粘贴的内容里提取 BV 号和分P。只取这两样，规范链接由我们自己拼，用户输入绝不进命令行。
 * b23.tv 短链：不自动跟随重定向，逐跳读 Location，只认 B 站自己的域名。
 */
@Component
public class BiliLinks {

    public record Video(String bvid, int page) {

        public String jobKey() {
            return bvid + "_p" + page;
        }

        public String url() {
            return "https://www.bilibili.com/video/" + bvid + "?p=" + page;
        }
    }

    private static final Pattern BV = Pattern.compile("(?i:bv)([0-9A-Za-z]{10})");
    private static final Pattern PAGE = Pattern.compile("[?&]p=(\\d{1,4})(?!\\d)");
    private static final Pattern SHORT = Pattern.compile("(?i)b23\\.tv/([0-9A-Za-z]{4,20})");

    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    public Video parse(String input) {
        if (input == null || input.isBlank()) {
            throw ApiException.badRequest("请粘贴 B 站视频链接或 BV 号");
        }
        String s = input.strip();
        if (s.length() > 1000) {
            throw ApiException.badRequest("链接太长");
        }
        if (!BV.matcher(s).find()) {
            Matcher shortLink = SHORT.matcher(s);
            if (shortLink.find()) {
                s = resolveShort(shortLink.group(1));
            }
        }
        Matcher bv = BV.matcher(s);
        if (!bv.find()) {
            throw ApiException.badRequest("没找到 BV 号：请粘贴 B 站视频链接或 BV 号");
        }
        int page = 1;
        Matcher p = PAGE.matcher(s);
        if (p.find()) {
            page = Math.max(1, Integer.parseInt(p.group(1)));
        }
        return new Video("BV" + bv.group(1), page);
    }

    private String resolveShort(String code) {
        String url = "https://b23.tv/" + code;
        try {
            for (int hop = 0; hop < 3; hop++) {
                HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(8))
                        .header("User-Agent", "Mozilla/5.0")
                        .GET()
                        .build();
                HttpResponse<Void> resp = http.send(req, HttpResponse.BodyHandlers.discarding());
                Optional<String> location = resp.headers().firstValue("location");
                if (location.isEmpty()) {
                    break;
                }
                URI next = URI.create(url).resolve(location.get());
                String host = next.getHost() == null ? "" : next.getHost().toLowerCase(Locale.ROOT);
                if (!host.endsWith("bilibili.com") && !host.equals("b23.tv")) {
                    break;
                }
                url = next.toString();
                if (BV.matcher(url).find()) {
                    return url;
                }
            }
        } catch (IOException | IllegalArgumentException e) {
            // 落到下面统一报错
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        throw ApiException.badRequest("短链解析失败：请直接粘贴视频链接");
    }
}
