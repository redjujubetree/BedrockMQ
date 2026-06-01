package top.redjujubetree.bedrock.mq.recovery;

import top.redjujubetree.bedrock.mq.config.BedrockMqProperties;
import top.redjujubetree.bedrock.mq.mapper.BedrockConsumeRecordMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TimeoutRecoveryTaskTest {

    @Mock BedrockConsumeRecordMapper consumeRecordMapper;
    @Mock BedrockMqProperties properties;

    TimeoutRecoveryTask recoveryTask;

    @BeforeEach
    void setUp() {
        when(properties.getProcessingTimeoutMinutes()).thenReturn(5);
        recoveryTask = new TimeoutRecoveryTask(consumeRecordMapper, properties);
    }

    @Test
    void recover_callsMapperWithConfiguredTimeoutMinutes() {
        when(consumeRecordMapper.recoverTimeoutRecords(5)).thenReturn(0);

        recoveryTask.recover();

        verify(consumeRecordMapper).recoverTimeoutRecords(5);
    }

    @Test
    void recover_completesNormallyWhenNoRecordsAreRecovered() {
        when(consumeRecordMapper.recoverTimeoutRecords(5)).thenReturn(0);

        assertDoesNotThrow(() -> recoveryTask.recover());
    }

    @Test
    void recover_completesNormallyWhenMultipleRecordsAreRecovered() {
        when(consumeRecordMapper.recoverTimeoutRecords(5)).thenReturn(3);

        assertDoesNotThrow(() -> recoveryTask.recover());
        verify(consumeRecordMapper, times(1)).recoverTimeoutRecords(5);
    }
}
