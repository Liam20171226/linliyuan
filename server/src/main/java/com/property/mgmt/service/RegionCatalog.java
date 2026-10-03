package com.property.mgmt.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

/** 全国省市区树（31 个省级行政区，不含港澳台），供选择器与校验使用 */
public final class RegionCatalog {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static volatile List<Map<String, Object>> cached;

    private RegionCatalog() {}

    public static List<Map<String, Object>> tree() {
        List<Map<String, Object>> local = cached;
        if (local != null) {
            return local;
        }
        synchronized (RegionCatalog.class) {
            if (cached != null) {
                return cached;
            }
            try (InputStream in = RegionCatalog.class.getResourceAsStream("/regions.json")) {
                if (in == null) {
                    throw new IllegalStateException("缺少 regions.json");
                }
                cached = MAPPER.readValue(in, new TypeReference<List<Map<String, Object>>>() {});
                return cached;
            } catch (Exception e) {
                throw new IllegalStateException("加载省市区数据失败", e);
            }
        }
    }

    public static void validate(String provinceCode, String cityCode, String districtCode,
                                String provinceName, String cityName, String districtName) {
        boolean ok = false;
        for (Map<String, Object> p : tree()) {
            if (!provinceCode.equals(p.get("code")) || !provinceName.equals(p.get("name"))) continue;
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> cities = (List<Map<String, Object>>) p.get("cities");
            if (cities == null) continue;
            for (Map<String, Object> c : cities) {
                if (!cityCode.equals(c.get("code")) || !cityName.equals(c.get("name"))) continue;
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> districts = (List<Map<String, Object>>) c.get("districts");
                if (districts == null) continue;
                for (Map<String, Object> d : districts) {
                    if (districtCode.equals(d.get("code")) && districtName.equals(d.get("name"))) {
                        ok = true;
                        break;
                    }
                }
            }
        }
        if (!ok) {
            throw com.property.mgmt.common.BizException.of(
                    com.property.mgmt.common.ErrorCodes.BAD_PARAM, "省市区无效或不匹配");
        }
    }

    /**
     * 入库/展示地址：{@code 省/市/区/} + 具体地址。
     * 例：{@code 北京市/北京市/东城区/桃园小区}；仅地区时末尾保留 {@code /}。
     * 省、市、区均写出（直辖市市名与省名相同也保留，便于展示一致）。
     */
    public static String composeAddress(String provinceName, String cityName, String districtName, String detail) {
        String p = provinceName == null ? "" : provinceName.trim();
        String c = cityName == null ? "" : cityName.trim();
        String d = districtName == null ? "" : districtName.trim();
        String t = detail == null ? "" : detail.trim();
        StringBuilder sb = new StringBuilder();
        if (!p.isEmpty()) {
            sb.append(p).append('/');
        }
        if (!c.isEmpty()) {
            sb.append(c).append('/');
        }
        if (!d.isEmpty()) {
            sb.append(d).append('/');
        }
        sb.append(t);
        return sb.toString();
    }

    /**
     * 有省市区字段时校验并拼接入库地址；否则沿用传入的 address（可为 null，表示不改）。
     */
    public static String resolveStoredAddress(String address,
                                              String provinceCode, String provinceName,
                                              String cityCode, String cityName,
                                              String districtCode, String districtName,
                                              String addressDetail) {
        boolean anyRegion = hasText(provinceCode) || hasText(cityCode) || hasText(districtCode)
                || hasText(provinceName) || hasText(cityName) || hasText(districtName)
                || hasText(addressDetail);
        if (!anyRegion) {
            return address;
        }
        if (!hasText(provinceCode) || !hasText(cityCode) || !hasText(districtCode)
                || !hasText(provinceName) || !hasText(cityName) || !hasText(districtName)) {
            throw com.property.mgmt.common.BizException.of(
                    com.property.mgmt.common.ErrorCodes.BAD_PARAM, "请选择完整省市区");
        }
        if (!hasText(addressDetail)) {
            throw com.property.mgmt.common.BizException.of(
                    com.property.mgmt.common.ErrorCodes.BAD_PARAM, "请填写具体地址");
        }
        validate(provinceCode, cityCode, districtCode, provinceName, cityName, districtName);
        return composeAddress(provinceName, cityName, districtName, addressDetail);
    }

    public record ParsedAddress(String provinceCode, String provinceName,
                                String cityCode, String cityName,
                                String districtCode, String districtName,
                                String detail) {}

    /** 从已存拼接地址尽量还原省市区与详细地址，供编辑回填（兼容旧无斜杠拼接） */
    public static ParsedAddress parseAddress(String address) {
        if (!hasText(address)) {
            return null;
        }
        ParsedAddress best = null;
        int bestLen = -1;
        for (Map<String, Object> p : tree()) {
            String pName = String.valueOf(p.get("name"));
            String pCode = String.valueOf(p.get("code"));
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> cities = (List<Map<String, Object>>) p.get("cities");
            if (cities == null) continue;
            for (Map<String, Object> c : cities) {
                String cName = String.valueOf(c.get("name"));
                String cCode = String.valueOf(c.get("code"));
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> districts = (List<Map<String, Object>>) c.get("districts");
                if (districts == null) continue;
                for (Map<String, Object> d : districts) {
                    String dName = String.valueOf(d.get("name"));
                    String dCode = String.valueOf(d.get("code"));
                    String slash = pName + "/" + cName + "/" + dName + "/";
                    String legacyFull = pName + cName + dName;
                    String legacyDedup = pName.equals(cName) ? pName + dName : legacyFull;
                    for (String prefix : new String[]{slash, legacyFull, legacyDedup}) {
                        if (address.startsWith(prefix) && prefix.length() > bestLen) {
                            bestLen = prefix.length();
                            best = new ParsedAddress(pCode, pName, cCode, cName, dCode, dName,
                                    address.substring(prefix.length()));
                        }
                    }
                }
            }
        }
        return best;
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }
}
