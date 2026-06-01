package top.redjujubetree.bedrock.mq.annotation;

import org.springframework.stereotype.Component;

import java.lang.annotation.*;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Component
public @interface BedrockConsumer {
    /** Consumer name (unique identity), must be unique per topic. Must not be empty. */
    String value();

    /** Topic to subscribe to. Required; must not be empty. */
    String topic();

    /** Default max retry count stored in bedrock_subscription. */
    int maxRetry() default 3;
}
