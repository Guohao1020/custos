package io.custos.identity;

/** 令牌无效：签名错/过期/被吊销/格式错。 */
public class TokenException extends RuntimeException {
    public TokenException(String message, Throwable cause) { super(message, cause); }
    public TokenException(String message) { super(message); }
}
