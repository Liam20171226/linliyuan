package com.property.mgmt.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Component
public class JwtService {

    private final SecretKey key;
    private final long expireHours;

    public JwtService(
            @Value("${app.jwt.secret}") String secret,
            @Value("${app.jwt.expire-hours:168}") long expireHours) {
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            byte[] padded = new byte[32];
            System.arraycopy(bytes, 0, padded, 0, bytes.length);
            bytes = padded;
        }
        this.key = Keys.hmacShaKeyFor(bytes);
        this.expireHours = expireHours;
    }

    public String issue(AuthUser user) {
        Instant now = Instant.now();
        Map<String, Object> claims = new HashMap<>();
        claims.put("userId", user.getUserId());
        if (user.getIdentityType() != null) {
            claims.put("identityType", user.getIdentityType());
        }
        if (user.getCommunityId() != null) {
            claims.put("communityId", user.getCommunityId());
        }
        if (user.getRoomId() != null) {
            claims.put("roomId", user.getRoomId());
        }
        if (user.getStaffRole() != null) {
            claims.put("staffRole", user.getStaffRole());
        }
        claims.put("platformAdmin", user.isPlatformAdmin());
        if (user.isWebStaffSession()) {
            claims.put("webStaffSession", true);
        }
        if (user.isAppPasswordSession()) {
            claims.put("appPasswordSession", true);
        }
        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .claims(claims)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(expireHours, ChronoUnit.HOURS)))
                .signWith(key)
                .compact();
    }

    /** 短期 sessionToken：仅携带 openid，用于手机号匹配前 */
    public String issueSessionToken(String openid) {
        Instant now = Instant.now();
        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .claim("openid", openid)
                .claim("type", "SESSION")
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(30, ChronoUnit.MINUTES)))
                .signWith(key)
                .compact();
    }

    public Claims parse(String token) {
        return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
    }

    public AuthUser toAuthUser(Claims claims) {
        Long userId = claims.get("userId", Number.class) == null
                ? null : claims.get("userId", Number.class).longValue();
        if (userId == null) {
            return null;
        }
        Number communityId = claims.get("communityId", Number.class);
        Number roomId = claims.get("roomId", Number.class);
        Boolean platformAdmin = claims.get("platformAdmin", Boolean.class);
        Boolean webStaffSession = claims.get("webStaffSession", Boolean.class);
        Boolean appPasswordSession = claims.get("appPasswordSession", Boolean.class);
        return AuthUser.builder()
                .userId(userId)
                .identityType(claims.get("identityType", String.class))
                .communityId(communityId == null ? null : communityId.longValue())
                .roomId(roomId == null ? null : roomId.longValue())
                .staffRole(claims.get("staffRole", String.class))
                .platformAdmin(Boolean.TRUE.equals(platformAdmin))
                .webStaffSession(Boolean.TRUE.equals(webStaffSession))
                .appPasswordSession(Boolean.TRUE.equals(appPasswordSession))
                .build();
    }
}
