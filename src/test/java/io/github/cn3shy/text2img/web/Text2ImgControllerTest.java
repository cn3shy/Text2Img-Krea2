package io.github.cn3shy.text2img.web;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.cn3shy.text2img.error.CapacityExceededException;
import io.github.cn3shy.text2img.service.GenerationCapacity;
import io.github.cn3shy.text2img.service.Text2ImgService;
import org.junit.jupiter.api.Test;

class Text2ImgControllerTest {

    private final Text2ImgService service = mock(Text2ImgService.class);
    private final GenerationCapacity capacity = mock(GenerationCapacity.class);
    private final Text2ImgController controller = new Text2ImgController(service, capacity);

    @Test
    void rejectsWhenCapacityIsExhausted() {
        when(capacity.tryAcquire()).thenReturn(false);

        assertThrows(CapacityExceededException.class,
                () -> controller.text2img(new Text2ImgRequest("cat", 1024, 1024)));
        verifyNoInteractions(service);
    }

    @Test
    void releasesCapacityEvenWhenGenerationFails() {
        when(capacity.tryAcquire()).thenReturn(true);
        when(service.generate(any())).thenThrow(new IllegalStateException("boom"));

        assertThrows(IllegalStateException.class,
                () -> controller.text2img(new Text2ImgRequest("cat", 1024, 1024)));

        verify(capacity).release();
    }
}
