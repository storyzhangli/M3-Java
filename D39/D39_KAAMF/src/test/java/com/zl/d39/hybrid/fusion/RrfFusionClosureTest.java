package com.zl.d39.hybrid.fusion;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D39-R01 C01 单测：RRF 融合闭包性——两路输入均为权限过滤后的名次表时，
 * 融合输出必为两路输入并集的子集（不可能凭空引入权限外 chunk；H4 融合闭包）。
 */
class RrfFusionClosureTest {

    private final RrfFusionService service = new RrfFusionService();

    @Test
    @DisplayName("闭包：输出 chunkId ⊆ 两路输入并集，且并集=权限集时输出 ⊆ 权限集")
    void fusedOutputIsSubsetOfRouteUnion() {
        List<String> permissionSet = List.of("c1", "c2", "c3", "c4");
        List<String> vectorRoute = List.of("c1", "c3");
        List<String> keywordRoute = List.of("c3", "c4", "c2");

        List<RrfFusionService.FusedCandidate> fused = service.fuse(vectorRoute, keywordRoute, 60, 10);
        List<String> outputIds = fused.stream().map(RrfFusionService.FusedCandidate::chunkId).toList();

        java.util.LinkedHashSet<String> union = new java.util.LinkedHashSet<>(vectorRoute);
        union.addAll(keywordRoute);
        assertThat(outputIds).isSubsetOf(permissionSet);
        assertThat(outputIds).containsExactlyInAnyOrderElementsOf(union);
    }

    @Test
    @DisplayName("权限外 chunk 若被上游正确过滤掉，绝不可能出现在融合结果（闭包反证形态）")
    void untaggedChunkNeverAppearsWhenRoutesFiltered() {
        List<String> vectorRoute = new ArrayList<>(List.of("c1"));
        List<String> keywordRoute = new ArrayList<>(List.of("c1"));
        List<RrfFusionService.FusedCandidate> fused = service.fuse(vectorRoute, keywordRoute, 60, 10);
        assertThat(fused).hasSize(1);
        assertThat(fused.get(0).chunkId()).isEqualTo("c1");
        assertThat(fused.get(0).hitSource()).isEqualTo("BOTH");
    }

    @Test
    @DisplayName("RRF 数值冻结口径：k=60，rank1 贡献 1/61，两路同名次相加")
    void rrfScoreExactSemantics() {
        List<RrfFusionService.FusedCandidate> fused =
                service.fuse(List.of("a", "b"), List.of("a", "b"), 60, 5);
        double first = 1.0 / 61 + 1.0 / 61;
        double second = 1.0 / 62 + 1.0 / 62;
        assertThat(fused.get(0).chunkId()).isEqualTo("a");
        assertThat(fused.get(0).rrfScore()).isEqualTo(first);
        assertThat(fused.get(1).rrfScore()).isEqualTo(second);
    }

    @Test
    @DisplayName("确定性：同分 chunkId 升序；topK 截断")
    void deterministicTieBreakAndTopK() {
        List<RrfFusionService.FusedCandidate> fused =
                service.fuse(List.of("z", "m"), List.of("m", "z"), 60, 1);
        // z: 1/61+1/62, m: 1/62+1/61 → 同分，chunkId 升序 → m 在前；topK=1 截断只剩 m
        assertThat(fused).hasSize(1);
        assertThat(fused.get(0).chunkId()).isEqualTo("m");
    }
}
