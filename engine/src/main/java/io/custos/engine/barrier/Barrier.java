package io.custos.engine.barrier;

/** 加密屏障：所有数据落盘前 seal（加密），读取后 open（解密+校验）。存储后端只见密文。 */
public interface Barrier {
    byte[] seal(byte[] plaintext);
    byte[] open(byte[] ciphertext);
}
