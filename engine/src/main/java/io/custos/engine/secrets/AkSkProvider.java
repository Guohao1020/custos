package io.custos.engine.secrets;

/** AK·SK 后端 SPI：现场铸/吊销。未来加 AwsStsProvider / AliyunRamProvider。 */
public interface AkSkProvider {
    AkSkPair mint(String mount);
    void revoke(String accessKeyId);
}
