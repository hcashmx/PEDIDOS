package com.cyssoftware.effyFlow;

import com.cyssoftware.effyFlow.controller.pedido.PedidoController;
import com.cyssoftware.effyFlow.service.pedido.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class PedidoControllerContractTest {
    @ParameterizedTest @ValueSource(ints={400,401,403,409,422,500,503,504})
    void httpSyncConservaErroresERP(int code) throws Exception {
        var service = mock(PedidoService.class);
        when(service.guardarPedidoDesdeApp(any())).thenThrow(new ErpCapturaException(code,
                PedidoErpAdapterTest.JSON.readTree("{\"message\":\"captura rechazada\"}")));
        var mvc = MockMvcBuilders.standaloneSetup(new PedidoController(service)).build();
        mvc.perform(post("/api/sync/pedidos").contentType("application/json")
                .content(PedidoErpAdapterTest.JSON.writeValueAsBytes(PedidoErpAdapterTest.payload())))
                .andExpect(status().is(code)).andExpect(jsonPath("$.origenError").value("ERP"))
                .andExpect(jsonPath("$.reintentable").value(code >= 500))
                .andExpect(jsonPath("$.detalleErp.message").value("captura rechazada"))
                .andExpect(jsonPath("$.uuidServer").doesNotExist());
    }
}
