package com.xiuxian.config;

import java.time.Duration;
import java.util.Arrays;

import org.springframework.boot.autoconfigure.web.WebProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 字体和第三方库体积大且不会变，让浏览器长期缓存；页面、脚本、样式走默认的 no-cache（每次校验，改了立刻生效）。
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final WebProperties webProperties;

    public WebConfig(WebProperties webProperties) {
        this.webProperties = webProperties;
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        CacheControl longCache = CacheControl.maxAge(Duration.ofDays(365)).cachePublic();
        registry.addResourceHandler("/fonts/**")
                .addResourceLocations(under("fonts/"))
                .setCacheControl(longCache);
        registry.addResourceHandler("/vendor/**")
                .addResourceLocations(under("vendor/"))
                .setCacheControl(longCache);
    }

    /** 跟随 spring.web.resources.static-locations，开发时可以直接指向源码目录。 */
    private String[] under(String sub) {
        return Arrays.stream(webProperties.getResources().getStaticLocations())
                .map(root -> (root.endsWith("/") ? root : root + "/") + sub)
                .toArray(String[]::new);
    }
}
