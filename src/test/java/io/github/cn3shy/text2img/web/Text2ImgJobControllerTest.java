package io.github.cn3shy.text2img.web;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.cn3shy.text2img.error.CapacityExceededException;
import io.github.cn3shy.text2img.error.JobNotFoundException;
import io.github.cn3shy.text2img.error.JobNotReadyException;
import io.github.cn3shy.text2img.service.GenerationJob;
import io.github.cn3shy.text2img.service.GenerationJobService;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

class Text2ImgJobControllerTest {

    private final GenerationJobService service = mock(GenerationJobService.class);
    private final Text2ImgJobController controller = new Text2ImgJobController(service);

    @Test
    void createReturns202WithJobIdAndLocation() {
        when(service.submit(any())).thenReturn(new GenerationJob("job-1", 1024, 1024, System::currentTimeMillis));

        ResponseEntity<Text2ImgJobController.JobCreatedResponse> response =
                controller.create(new Text2ImgRequest("cat", 1024, 1024));

        assertEquals(202, response.getStatusCode().value());
        assertEquals("/text2img/jobs/job-1", response.getHeaders().getLocation().toString());
        assertEquals("job-1", response.getBody().jobId());
        assertEquals("running", response.getBody().status());
        assertEquals(1000, response.getBody().pollAfterMs());
    }

    @Test
    void unknownJobThrowsNotFound() {
        when(service.get("missing")).thenThrow(new JobNotFoundException());

        assertThrows(JobNotFoundException.class, () -> controller.status("missing"));
    }

    @Test
    void imageNotReadyThrowsConflict() {
        when(service.getImage("job-1")).thenThrow(new JobNotReadyException());

        assertThrows(JobNotReadyException.class, () -> controller.image("job-1"));
    }

    @Test
    void imageReturnsPngBytes() {
        when(service.getImage("job-1")).thenReturn(new byte[] {1, 2, 3});

        ResponseEntity<byte[]> response = controller.image("job-1");

        assertEquals(200, response.getStatusCode().value());
        assertEquals("image/png", response.getHeaders().getContentType().toString());
        assertArrayEquals(new byte[] {1, 2, 3}, response.getBody());
    }

    @Test
    void capacityExceededPropagates() {
        when(service.submit(any())).thenThrow(new CapacityExceededException());

        assertThrows(CapacityExceededException.class,
                () -> controller.create(new Text2ImgRequest("cat", 1024, 1024)));
    }
}
