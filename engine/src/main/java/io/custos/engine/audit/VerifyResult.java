package io.custos.engine.audit;

/** 哈希链校验结果。ok=true 时 brokenAtSeq=-1；否则为首个断链行的 seq。 */
public record VerifyResult(boolean ok, long brokenAtSeq) {
    public static VerifyResult passed() { return new VerifyResult(true, -1); }
    public static VerifyResult brokenAt(long seq) { return new VerifyResult(false, seq); }
}
