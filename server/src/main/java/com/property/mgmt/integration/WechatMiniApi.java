package com.property.mgmt.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.property.mgmt.common.BizException;
import com.property.mgmt.common.ErrorCodes;
import com.property.mgmt.config.WxProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 微信小程序开放接口：登录、手机号、订阅消息。
 * {@code app.wx.mock=true} 时走本地假数据，便于无 AppSecret 开发。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WechatMiniApi {

    private final WxProperties wx;
    private final ObjectMapper objectMapper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    private final AtomicReference<CachedToken> tokenCache = new AtomicReference<>();

    public boolean isMock() {
        return wx.isMock();
    }

    public String resolveOpenid(String code) {
        if (code == null || code.isBlank()) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "code 不能为空");
        }
        if (wx.isMock()) {
            return code.startsWith("dev_") ? code : ("dev_" + code);
        }
        try {
            String url = "https://api.weixin.qq.com/sns/jscode2session"
                    + "?appid=" + enc(wx.getAppId())
                    + "&secret=" + enc(wx.getSecret())
                    + "&js_code=" + enc(code)
                    + "&grant_type=authorization_code";
            JsonNode node = getJson(url);
            if (node.hasNonNull("errcode") && node.get("errcode").asInt() != 0) {
                throw BizException.of(ErrorCodes.BAD_PARAM,
                        "微信登录失败: " + node.path("errmsg").asText("unknown"));
            }
            String openid = node.path("openid").asText(null);
            if (openid == null || openid.isBlank()) {
                throw BizException.of(ErrorCodes.BAD_PARAM, "微信未返回 openid");
            }
            return openid;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.error("code2session failed", e);
            throw BizException.of(ErrorCodes.SERVER, "微信登录调用失败");
        }
    }

    /**
     * mock：phoneCode 直接当作手机号；正式：phoneCode 为 getPhoneNumber 返回的 code。
     */
    public String resolveMobile(String phoneCode) {
        if (phoneCode == null || phoneCode.isBlank()) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "phoneCode 不能为空");
        }
        if (wx.isMock()) {
            return phoneCode.trim();
        }
        try {
            String token = accessToken();
            String url = "https://api.weixin.qq.com/wxa/business/getuserphonenumber?access_token=" + enc(token);
            Map<String, Object> body = Map.of("code", phoneCode.trim());
            JsonNode node = postJson(url, body);
            if (node.hasNonNull("errcode") && node.get("errcode").asInt() != 0) {
                throw BizException.of(ErrorCodes.BAD_PARAM,
                        "获取手机号失败: " + node.path("errmsg").asText("unknown"));
            }
            String mobile = node.path("phone_info").path("purePhoneNumber").asText(null);
            if (mobile == null || mobile.isBlank()) {
                mobile = node.path("phone_info").path("phoneNumber").asText(null);
            }
            if (mobile == null || mobile.isBlank()) {
                throw BizException.of(ErrorCodes.BAD_PARAM, "微信未返回手机号");
            }
            return mobile.trim();
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.error("getuserphonenumber failed", e);
            throw BizException.of(ErrorCodes.SERVER, "微信手机号调用失败");
        }
    }

    /**
     * @return SUCCESS / FAIL / SKIPPED 与错误信息
     */
    public SendResult sendSubscribe(String openid, String scene, String title, String content, String page) {
        if (openid == null || openid.isBlank()) {
            return SendResult.skipped("无 openid");
        }
        if (wx.isMock()) {
            return SendResult.skipped("app.wx.mock=true，未真实下发");
        }
        String templateId = wx.templateId(scene);
        if (templateId == null || templateId.isBlank()) {
            return SendResult.skipped("未配置模板 app.wx.templates." + scene);
        }
        try {
            String token = accessToken();
            String url = "https://api.weixin.qq.com/cgi-bin/message/subscribe/send?access_token=" + enc(token);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("thing1", Map.of("value", clip(title, 20)));
            data.put("thing2", Map.of("value", clip(content == null || content.isBlank() ? title : content, 20)));
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("touser", openid);
            body.put("template_id", templateId);
            body.put("page", page == null || page.isBlank() ? "pages/index/index" : page);
            body.put("data", data);
            body.put("miniprogram_state", "formal");
            JsonNode node = postJson(url, body);
            int err = node.path("errcode").asInt(0);
            if (err != 0) {
                return SendResult.fail(node.path("errmsg").asText("errcode=" + err));
            }
            return SendResult.success();
        } catch (Exception e) {
            log.warn("subscribe send failed scene={}", scene, e);
            return SendResult.fail(e.getMessage());
        }
    }

    public synchronized String accessToken() throws Exception {
        CachedToken cached = tokenCache.get();
        long now = System.currentTimeMillis();
        if (cached != null && cached.expireAtMs > now + 60_000) {
            return cached.token;
        }
        String url = "https://api.weixin.qq.com/cgi-bin/token"
                + "?grant_type=client_credential"
                + "&appid=" + enc(wx.getAppId())
                + "&secret=" + enc(wx.getSecret());
        JsonNode node = getJson(url);
        if (node.hasNonNull("errcode") && node.get("errcode").asInt() != 0) {
            throw new IllegalStateException("access_token: " + node.path("errmsg").asText());
        }
        String token = node.path("access_token").asText(null);
        int expiresIn = node.path("expires_in").asInt(7200);
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("access_token empty");
        }
        tokenCache.set(new CachedToken(token, now + expiresIn * 1000L));
        return token;
    }

    private JsonNode getJson(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return objectMapper.readTree(resp.body());
    }

    private JsonNode postJson(String url, Object body) throws Exception {
        String json = objectMapper.writeValueAsString(body);
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return objectMapper.readTree(resp.body());
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String clip(String s, int max) {
        if (s == null) {
            return "-";
        }
        String t = s.trim();
        return t.length() <= max ? t : t.substring(0, max);
    }

    private record CachedToken(String token, long expireAtMs) {}

    public record SendResult(String status, String errorMsg) {
        public static SendResult success() {
            return new SendResult("SUCCESS", null);
        }

        public static SendResult fail(String msg) {
            return new SendResult("FAIL", msg);
        }

        public static SendResult skipped(String msg) {
            return new SendResult("SKIPPED", msg);
        }
    }
}
