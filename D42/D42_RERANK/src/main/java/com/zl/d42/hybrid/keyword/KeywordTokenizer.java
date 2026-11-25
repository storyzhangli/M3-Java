package com.zl.d42.hybrid.keyword;

import com.zl.d42.document.embedding.local.TextFeatureExtractor;
import com.zl.d42.hybrid.config.HybridProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 关键词分词器：只读复用既有 {@link TextFeatureExtractor}（零修改、零新依赖），
 * 按冻结 featureSubset（w+g）过滤特征前缀；c: 单字符特征默认停用（任务书 §3.4）。
 *
 * <p>KP3 实测行为（证据 probe/kp3-features.txt）：
 * {@code E101 → w:e101 整词保留}；{@code 20% → w:20}（%非 token 字符）；
 * {@code 15分钟 → w:15分钟}（数字与 CJK 合并为单一 w: token）；
 * 中文部分匹配由 {@code g:} bigram 承担。</p>
 */
@Component
@ConditionalOnProperty(prefix = "app.hybrid", name = "enabled", havingValue = "true")
public class KeywordTokenizer {

    private final Set<String> allowedPrefixes;

    public KeywordTokenizer(@Qualifier("hybridProperties") HybridProperties properties) {
        Set<String> prefixes = new LinkedHashSet<>();
        for (String feature : properties.getFeatureSubset()) {
            if (feature == null) {
                continue;
            }
            String normalized = feature.trim().toLowerCase();
            if ("w".equals(normalized) || "c".equals(normalized) || "g".equals(normalized)) {
                prefixes.add(normalized + ":");
            } else {
                throw new IllegalArgumentException(
                        "app.hybrid.feature-subset 仅允许 w/c/g，实际: " + feature);
            }
        }
        if (prefixes.isEmpty()) {
            throw new IllegalArgumentException("app.hybrid.feature-subset 不能为空");
        }
        this.allowedPrefixes = Set.copyOf(prefixes);
    }

    /** 提取（保留重复与出现顺序，用于 tf 计数）。 */
    public List<String> extract(String text) {
        List<String> all = TextFeatureExtractor.extract(text);
        List<String> filtered = new ArrayList<>(all.size());
        for (String feature : all) {
            if (feature.length() >= 2 && allowedPrefixes.contains(feature.substring(0, 2))) {
                filtered.add(feature);
            }
        }
        return filtered;
    }

    /** 去重且按字典序排列的查询项（跨进程确定性求和顺序，任务书 §3.3）。 */
    public List<String> distinctSortedTerms(String text) {
        Set<String> unique = new LinkedHashSet<>(extract(text));
        List<String> sorted = new ArrayList<>(unique);
        java.util.Collections.sort(sorted);
        return sorted;
    }

    public Set<String> allowedPrefixes() {
        return allowedPrefixes;
    }
}
