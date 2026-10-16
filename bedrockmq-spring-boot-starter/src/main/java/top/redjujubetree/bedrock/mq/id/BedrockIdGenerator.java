package top.redjujubetree.bedrock.mq.id;

/** Generates a positive, unique ID representable as a long for the supplied entity before insertion. */
public interface BedrockIdGenerator {
    Number nextId(Object entity);
}
