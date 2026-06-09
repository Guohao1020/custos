package io.custos.engine.seal;

/** 引擎处于 sealed 状态时执行需要密钥的操作即抛此异常。 */
public class SealedException extends RuntimeException {
    public SealedException() {
        super("engine is sealed; submit unseal keys first");
    }
}
