package io.custos.engine.seal;

/** 密封状态。progress = 已提交的有效分片数；threshold = 解封所需阈值。 */
public record SealStatus(boolean sealed, int progress, int threshold) {}
