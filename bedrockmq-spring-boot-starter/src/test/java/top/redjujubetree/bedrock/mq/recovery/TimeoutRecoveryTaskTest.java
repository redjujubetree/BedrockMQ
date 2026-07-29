package top.redjujubetree.bedrock.mq.recovery;

import top.redjujubetree.bedrock.mq.mapper.BedrockConsumeRecordMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TimeoutRecoveryTaskTest {

    @Mock BedrockConsumeRecordMapper consumeRecordMapper;

    TimeoutRecoveryTask recoveryTask;

    @BeforeEach
    void setUp() {
        recoveryTask = new TimeoutRecoveryTask(consumeRecordMapper);
    }

    @Test
    void recover_callsMapperWithCurrentTime() {
        when(consumeRecordMapper.recoverTimedOutRecords(any())).thenReturn(0);

        recoveryTask.recover();

        org.mockito.ArgumentCaptor<LocalDateTime> nowCaptor =
                org.mockito.ArgumentCaptor.forClass(LocalDateTime.class);
        verify(consumeRecordMapper).recoverTimedOutRecords(nowCaptor.capture());
    }

    @Test
    void recover_completesNormallyWhenNoRecordsAreRecovered() {
        when(consumeRecordMapper.recoverTimedOutRecords(any())).thenReturn(0);

        assertDoesNotThrow(() -> recoveryTask.recover());
    }

    @Test
    void recover_completesNormallyWhenMultipleRecordsAreRecovered() {
        when(consumeRecordMapper.recoverTimedOutRecords(any())).thenReturn(3);

        assertDoesNotThrow(() -> recoveryTask.recover());
        verify(consumeRecordMapper, times(1)).recoverTimedOutRecords(any());
    }

    @Test
    void recoverSafely_swallowsDatabaseFailureSoSchedulingCanContinue() {
        when(consumeRecordMapper.recoverTimedOutRecords(any()))
                .thenThrow(new RuntimeException("database unavailable"));

        assertDoesNotThrow(() -> recoveryTask.recoverSafely());
    }
}
