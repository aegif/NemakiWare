package jp.aegif.nemaki.rest.purview.journal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link DirectLineageEmitter}.
 */
class DirectLineageEmitterTest {

    private LineageConfig mockConfig;
    private LineageTargetSink mockSink;

    @BeforeEach
    void setUp() {
        mockConfig = mock(LineageConfig.class);
        mockSink = mock(LineageTargetSink.class);
        when(mockSink.targetName()).thenReturn("purview");
        when(mockSink.isAvailable()).thenReturn(true);
    }

    @Test
    void emit_dispatchesToAvailableSink() throws Exception {
        when(mockSink.publish(any())).thenReturn(LineageTargetSinkResult.success(1, "OK"));

        DirectLineageEmitter emitter = new DirectLineageEmitter(mockConfig, List.of(mockSink));
        LineageEvent event = new LineageEventBuilder()
                .repositoryId("bedroom")
                .processType(LineageProcessType.IMPORT_UPLOADED)
                .addInputObject("bedroom", "doc-1")
                .build();

        emitter.emit(event);

        // Allow async dispatch time
        Thread.sleep(200);
        verify(mockSink).publish(event);
    }

    @Test
    void emit_skipsUnavailableSink() throws Exception {
        when(mockSink.isAvailable()).thenReturn(false);

        DirectLineageEmitter emitter = new DirectLineageEmitter(mockConfig, List.of(mockSink));
        LineageEvent event = new LineageEventBuilder()
                .repositoryId("bedroom")
                .processType(LineageProcessType.IMPORT_UPLOADED)
                .addInputObject("bedroom", "doc-1")
                .build();

        emitter.emit(event);

        Thread.sleep(200);
        verify(mockSink, never()).publish(any());
    }

    @Test
    void emit_nullEventIsIgnored() throws Exception {
        DirectLineageEmitter emitter = new DirectLineageEmitter(mockConfig, List.of(mockSink));
        emitter.emit(null);
        verify(mockSink, never()).publish(any());
    }

    @Test
    void emit_incrementsFailureCountOnPublishException() throws Exception {
        when(mockSink.publish(any())).thenThrow(new RuntimeException("Connection refused"));

        DirectLineageEmitter emitter = new DirectLineageEmitter(mockConfig, List.of(mockSink));
        LineageEvent event = new LineageEventBuilder()
                .repositoryId("bedroom")
                .processType(LineageProcessType.EXPORT_ZIP_FOLDER)
                .addInputObject("bedroom", "folder-1")
                .build();

        emitter.emit(event);

        Thread.sleep(200);
        assertEquals(1, emitter.getFailureCount());
    }

    @Test
    void emit_incrementsFailureCountOnFailureResult() throws Exception {
        when(mockSink.publish(any())).thenReturn(LineageTargetSinkResult.failure("API error"));

        DirectLineageEmitter emitter = new DirectLineageEmitter(mockConfig, List.of(mockSink));
        LineageEvent event = new LineageEventBuilder()
                .repositoryId("bedroom")
                .processType(LineageProcessType.CLOUD_SYNC_UPLOAD)
                .addInputObject("bedroom", "doc-2")
                .build();

        emitter.emit(event);

        Thread.sleep(200);
        assertEquals(1, emitter.getFailureCount());
    }

    @Test
    void emit_noSinks_logOnlyMode() {
        DirectLineageEmitter emitter = new DirectLineageEmitter(mockConfig);
        LineageEvent event = new LineageEventBuilder()
                .repositoryId("bedroom")
                .processType(LineageProcessType.IMPORT_UPLOADED)
                .addInputObject("bedroom", "doc-1")
                .build();

        // Should not throw
        emitter.emit(event);
        assertEquals(0, emitter.getFailureCount());
    }

    @Test
    void isActive_alwaysReturnsTrue() {
        DirectLineageEmitter emitter = new DirectLineageEmitter(mockConfig, List.of(mockSink));
        assertTrue(emitter.isActive());
    }

    @Test
    void emit_dispatchesToMultipleSinks() throws Exception {
        LineageTargetSink mockSink2 = mock(LineageTargetSink.class);
        when(mockSink2.targetName()).thenReturn("atlas");
        when(mockSink2.isAvailable()).thenReturn(true);
        when(mockSink.publish(any())).thenReturn(LineageTargetSinkResult.success(1, "OK"));
        when(mockSink2.publish(any())).thenReturn(LineageTargetSinkResult.success(1, "OK"));

        DirectLineageEmitter emitter = new DirectLineageEmitter(mockConfig, List.of(mockSink, mockSink2));
        LineageEvent event = new LineageEventBuilder()
                .repositoryId("bedroom")
                .processType(LineageProcessType.IMPORT_UPLOADED)
                .addInputObject("bedroom", "doc-1")
                .build();

        emitter.emit(event);

        Thread.sleep(300);
        verify(mockSink).publish(event);
        verify(mockSink2).publish(event);
    }
}
