package com.zl.d28.document.chunk;

import com.zl.d28.document.chunk.config.ChunkProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 切分配置校验测试：默认值、非法参数拒绝与边界条件。
 */
class ChunkPropertiesTest {

    @Test
    void defaultsAreValid() {
        ChunkProperties props = new ChunkProperties();
        assertThatCode(props::validate).doesNotThrowAnyException();
        assertThat(props.getChunkSize()).isEqualTo(500);
        assertThat(props.getChunkOverlap()).isEqualTo(100);
        assertThat(props.getMinChunkSize()).isEqualTo(50);
        assertThat(props.getMaxChunkSize()).isEqualTo(1000);
        assertThat(props.getLengthUnit()).isEqualTo(ChunkProperties.LENGTH_UNIT);
    }

    @Test
    void overlapEqualToSizeIsRejected() {
        ChunkProperties props = ChunkTestSamples.properties();
        props.setChunkOverlap(props.getChunkSize());
        assertThatThrownBy(props::validate)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("chunkOverlap");
    }

    @Test
    void overlapGreaterThanSizeIsRejected() {
        ChunkProperties props = ChunkTestSamples.properties();
        props.setChunkOverlap(props.getChunkSize() + 10);
        assertThatThrownBy(props::validate).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void negativeOverlapIsRejected() {
        ChunkProperties props = ChunkTestSamples.properties();
        props.setChunkOverlap(-1);
        assertThatThrownBy(props::validate).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void zeroSizeIsRejected() {
        ChunkProperties props = ChunkTestSamples.properties();
        props.setChunkSize(0);
        assertThatThrownBy(props::validate).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void minGreaterThanSizeIsRejected() {
        ChunkProperties props = ChunkTestSamples.properties();
        props.setMinChunkSize(props.getChunkSize() + 1);
        assertThatThrownBy(props::validate).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void maxSmallerThanSizeIsRejected() {
        ChunkProperties props = ChunkTestSamples.properties();
        props.setMaxChunkSize(props.getChunkSize() - 1);
        assertThatThrownBy(props::validate).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void toleranceOutOfRangeIsRejected() {
        ChunkProperties props = ChunkTestSamples.properties();
        props.setBoundaryTolerance(props.getChunkSize() + 5);
        assertThatThrownBy(props::validate).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nonUtf16UnitIsRejected() {
        ChunkProperties props = ChunkTestSamples.properties();
        props.setLengthUnit("TOKEN");
        assertThatThrownBy(props::validate).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void zeroOverlapIsAccepted() {
        ChunkProperties props = ChunkTestSamples.properties();
        props.setChunkOverlap(0);
        assertThat(props.getChunkOverlap()).isZero();
        assertThatCode(props::validate).doesNotThrowAnyException();
    }
}
