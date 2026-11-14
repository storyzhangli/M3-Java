package com.zl.d31.document.embedding.model;

/**
 * 相似度/距离度量方式（Distance Metric）。
 * 本项目默认使用余弦相似度（Cosine Similarity），向量默认 L2 归一化。
 */
public enum DistanceMetric {

    /** 余弦相似度：衡量方向夹角，对向量长度不敏感。 */
    COSINE,

    /** 点积：同时受方向与模长影响。 */
    DOT_PRODUCT,

    /** 欧氏距离：衡量空间直线距离，值越小越相似。 */
    EUCLIDEAN
}
