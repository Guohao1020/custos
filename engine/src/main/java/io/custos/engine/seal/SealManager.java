package io.custos.engine.seal;

import java.util.List;

public interface SealManager {

    /** 初始化：生成 master/barrier key，切分 unseal 分片（仅此一次返回），落盘加密产物。 */
    List<byte[]> init(int shares, int threshold);

    /** 提交一个 unseal 分片；达阈值则解封。 */
    SealStatus submitUnsealKey(byte[] share);

    /** 丢弃内存中的 master/barrier key，重新 sealed。 */
    void seal();

    SealStatus status();
}
