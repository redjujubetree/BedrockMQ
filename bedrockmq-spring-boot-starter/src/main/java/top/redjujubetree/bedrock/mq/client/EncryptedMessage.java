package top.redjujubetree.bedrock.mq.client;

public class EncryptedMessage {
    private String client;
    private String iv;
    private String ciphertext;
    public EncryptedMessage() {}
    public EncryptedMessage(String iv, String ciphertext) {
        this.iv = iv;
        this.ciphertext = ciphertext;
    }
    public String getIv() { return iv; }
    public String getClient() { return client; }
    public void setClient(String client) { this.client = client; }
    public void setIv(String iv) { this.iv = iv; }
    public String getCiphertext() { return ciphertext; }
    public void setCiphertext(String ciphertext) { this.ciphertext = ciphertext; }
}
