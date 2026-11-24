package dev.wattup.backend.station;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.CannotCreateTransactionException;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class StationApiAdviceTests {
    @Test
    void returnsAServiceUnavailableErrorWhenTheTransactionCannotOpen() throws Exception {
        var service = mock(ChargingStationService.class);
        when(service.search(any())).thenThrow(new CannotCreateTransactionException("internal database error"));
        var mvc = MockMvcBuilders.standaloneSetup(new ChargingStationController(service))
                .setControllerAdvice(new StationApiAdvice()).build();

        mvc.perform(get("/api/charging-stations").param("latitude", "35").param("longitude", "128"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("UPSTREAM_UNAVAILABLE"))
                .andExpect(jsonPath("$.requestId").isNotEmpty())
                .andExpect(content().string(not(containsString("internal database error"))));
    }
}
