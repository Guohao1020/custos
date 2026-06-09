package io.custos.engine.crypto;

/** 密文 tag 校验失败：数据被篡改或密钥/AAD 不符。读取方必须中止处理。 */
public class IntegrityException extends RuntimeException {
    public IntegrityException(String message, Throwable cause) {
        super(message, cause);
    }
}
