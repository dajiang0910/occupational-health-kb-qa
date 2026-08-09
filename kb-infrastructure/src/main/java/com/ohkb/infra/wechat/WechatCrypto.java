package com.ohkb.infra.wechat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Base64;

/**
 * 企业微信消息加解密工具。
 * <p>
 * 实现企微回调消息的 AES-256-CBC 加解密和 SHA1 签名校验。
 * 参考：<a href="https://developer.work.weixin.qq.com/document/path/90968">企微加解密文档</a>
 */
public class WechatCrypto {

    private static final Logger log = LoggerFactory.getLogger(WechatCrypto.class);

    private static final String AES_ALGORITHM = "AES/CBC/NoPadding";
    private static final int RANDOM_BYTES_LENGTH = 16;
    private static final int MSG_LENGTH_BYTES = 4;

    private final byte[] aesKey;
    private final String token;
    private final String corpId;

    /**
     * @param token          企微回调配置的 Token
     * @param encodingAesKey 企微回调配置的 EncodingAESKey（43 位 Base64）
     * @param corpId         企业 ID
     */
    public WechatCrypto(String token, String encodingAesKey, String corpId) {
        this.token = token;
        this.aesKey = Base64.getDecoder().decode(encodingAesKey + "=");
        this.corpId = corpId;
    }

    /**
     * 验证 URL 时解密 echostr。
     */
    public String verifyUrl(String msgSignature, String timestamp, String nonce, String echostr) {
        try {
            // 1. 校验签名
            String signature = sha1(token, timestamp, nonce, echostr);
            if (!signature.equals(msgSignature)) {
                log.warn("[WECHAT-CRYPTO] URL verify: signature mismatch");
                throw new WechatCryptoException("签名校验失败");
            }

            // 2. 解密 echostr
            String decrypted = decrypt(echostr);
            log.info("[WECHAT-CRYPTO] URL verification succeeded");
            return decrypted;

        } catch (WechatCryptoException e) {
            throw e;
        } catch (Exception e) {
            log.error("[WECHAT-CRYPTO] URL verification failed", e);
            throw new WechatCryptoException("URL 验证失败", e);
        }
    }

    /**
     * 解密企微推送的消息。
     *
     * @param msgSignature 消息签名
     * @param timestamp    时间戳
     * @param nonce        随机数
     * @param encryptedXml 加密的 XML 消息体
     * @return 解密后的明文 XML（不含 encrypt 标签的原始 XML）
     */
    public String decryptMessage(String msgSignature, String timestamp, String nonce,
                                  String encryptedXml) {
        try {
            // 1. 提取 <Encrypt> 标签内容
            String encrypt = extractCdata(encryptedXml, "Encrypt");
            if (encrypt == null) {
                throw new WechatCryptoException("消息中未找到 Encrypt 字段");
            }

            // 2. 校验签名
            String signature = sha1(token, timestamp, nonce, encrypt);
            if (!signature.equals(msgSignature)) {
                log.warn("[WECHAT-CRYPTO] Message signature mismatch");
                throw new WechatCryptoException("消息签名校验失败");
            }

            // 3. 解密
            String decrypted = decrypt(encrypt);
            log.debug("[WECHAT-CRYPTO] Message decrypted: {} chars", decrypted.length());
            return decrypted;

        } catch (WechatCryptoException e) {
            throw e;
        } catch (Exception e) {
            log.error("[WECHAT-CRYPTO] Message decryption failed", e);
            throw new WechatCryptoException("消息解密失败", e);
        }
    }

    /**
     * 加密回复消息。
     *
     * @param replyXml 明文回复 XML
     * @return 加密后的 XML（包含 Encrypt 和 MsgSignature 等）
     */
    public String encryptMessage(String replyXml, String timestamp, String nonce) {
        try {
            String encrypted = encrypt(replyXml);
            String signature = sha1(token, timestamp, nonce, encrypted);

            return String.format(
                    "<xml>" +
                    "<Encrypt><![CDATA[%s]]></Encrypt>" +
                    "<MsgSignature><![CDATA[%s]]></MsgSignature>" +
                    "<TimeStamp>%s</TimeStamp>" +
                    "<Nonce><![CDATA[%s]]></Nonce>" +
                    "</xml>",
                    encrypted, signature, timestamp, nonce
            );

        } catch (Exception e) {
            log.error("[WECHAT-CRYPTO] Message encryption failed", e);
            throw new WechatCryptoException("消息加密失败", e);
        }
    }

    // ── 加解密核心 ──

    /**
     * AES-256-CBC 解密。
     * <p>
     * 密文格式：Base64( AES_Encrypt(random(16) + msg_len(4, network order) + msg + corpId) )
     */
    private String decrypt(String encryptedBase64) throws Exception {
        byte[] ciphertext = Base64.getDecoder().decode(encryptedBase64);

        Cipher cipher = Cipher.getInstance(AES_ALGORITHM);
        SecretKeySpec keySpec = new SecretKeySpec(aesKey, "AES");
        IvParameterSpec ivSpec = new IvParameterSpec(aesKey, 0, 16);
        cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec);

        byte[] decrypted = cipher.doFinal(ciphertext);

        // 去除 PKCS#7 填充
        int padLen = decrypted[decrypted.length - 1] & 0xFF;
        byte[] unpadded = Arrays.copyOf(decrypted, decrypted.length - padLen);

        // 解析：random(16) + msg_len(4) + msg + corpId
        int msgLen = bytesToInt(unpadded, RANDOM_BYTES_LENGTH);
        byte[] msgBytes = Arrays.copyOfRange(unpadded,
                RANDOM_BYTES_LENGTH + MSG_LENGTH_BYTES,
                RANDOM_BYTES_LENGTH + MSG_LENGTH_BYTES + msgLen);
        String msg = new String(msgBytes, StandardCharsets.UTF_8);

        // 验证 corpId
        byte[] corpIdBytes = Arrays.copyOfRange(unpadded,
                RANDOM_BYTES_LENGTH + MSG_LENGTH_BYTES + msgLen,
                unpadded.length);
        String receivedCorpId = new String(corpIdBytes, StandardCharsets.UTF_8);

        if (!corpId.equals(receivedCorpId)) {
            log.warn("[WECHAT-CRYPTO] CorpId mismatch: expected={}, received={}",
                    corpId, receivedCorpId);
        }

        return msg;
    }

    /**
     * AES-256-CBC 加密。
     * <p>
     * 明文格式：random(16) + msg_len(4, network order) + msg + corpId
     * 输出：Base64( AES_Encrypt(...) )
     */
    private String encrypt(String plainText) throws Exception {
        byte[] random = new byte[RANDOM_BYTES_LENGTH];
        new java.security.SecureRandom().nextBytes(random);
        byte[] msgBytes = plainText.getBytes(StandardCharsets.UTF_8);
        byte[] msgLenBytes = intToBytes(msgBytes.length);
        byte[] corpIdBytes = corpId.getBytes(StandardCharsets.UTF_8);

        byte[] plain = new byte[RANDOM_BYTES_LENGTH + MSG_LENGTH_BYTES + msgBytes.length + corpIdBytes.length];
        System.arraycopy(random, 0, plain, 0, RANDOM_BYTES_LENGTH);
        System.arraycopy(msgLenBytes, 0, plain, RANDOM_BYTES_LENGTH, MSG_LENGTH_BYTES);
        System.arraycopy(msgBytes, 0, plain, RANDOM_BYTES_LENGTH + MSG_LENGTH_BYTES, msgBytes.length);
        System.arraycopy(corpIdBytes, 0, plain,
                RANDOM_BYTES_LENGTH + MSG_LENGTH_BYTES + msgBytes.length, corpIdBytes.length);

        // PKCS#7 填充
        int blockSize = 32;
        int padLen = blockSize - (plain.length % blockSize);
        byte[] padded = new byte[plain.length + padLen];
        System.arraycopy(plain, 0, padded, 0, plain.length);
        Arrays.fill(padded, plain.length, padded.length, (byte) padLen);

        Cipher cipher = Cipher.getInstance(AES_ALGORITHM);
        SecretKeySpec keySpec = new SecretKeySpec(aesKey, "AES");
        IvParameterSpec ivSpec = new IvParameterSpec(aesKey, 0, 16);
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec);

        byte[] encrypted = cipher.doFinal(padded);
        return Base64.getEncoder().encodeToString(encrypted);
    }

    // ── 签名 ──

    /**
     * SHA1 签名：sha1(sort(token, timestamp, nonce, encrypt))
     */
    private String sha1(String... parts) {
        try {
            Arrays.sort(parts);
            String combined = String.join("", parts);
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] digest = md.digest(combined.getBytes(StandardCharsets.UTF_8));
            return bytesToHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new WechatCryptoException("SHA-1 算法不可用", e);
        }
    }

    // ── XML helper ──

    private String extractCdata(String xml, String tagName) {
        String openTag = "<" + tagName + ">";
        String closeTag = "</" + tagName + ">";
        int start = xml.indexOf(openTag);
        int end = xml.indexOf(closeTag);
        if (start < 0 || end < 0) return null;

        String content = xml.substring(start + openTag.length(), end);
        // Strip CDATA wrapper if present
        if (content.startsWith("<![CDATA[") && content.endsWith("]]>")) {
            content = content.substring(9, content.length() - 3);
        }
        return content;
    }

    // ── 字节工具 ──

    private static int bytesToInt(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xFF) << 24)
                | ((bytes[offset + 1] & 0xFF) << 16)
                | ((bytes[offset + 2] & 0xFF) << 8)
                | (bytes[offset + 3] & 0xFF);
    }

    private static byte[] intToBytes(int value) {
        return new byte[]{
                (byte) ((value >> 24) & 0xFF),
                (byte) ((value >> 16) & 0xFF),
                (byte) ((value >> 8) & 0xFF),
                (byte) (value & 0xFF)
        };
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /**
     * 加解密异常。
     */
    public static class WechatCryptoException extends RuntimeException {
        public WechatCryptoException(String message) {
            super(message);
        }

        public WechatCryptoException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
