package com.cyssoftware.effyFlow;

import com.cyssoftware.effyFlow.service.cotizaciones.RevisionComercialSyncService;
import com.cyssoftware.effyFlow.service.pedido.ErpPedidoClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Properties;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import static org.junit.jupiter.api.Assertions.*;

/** GET-only check against local ERP. No catalog or business records are changed. */
@EnabledIfEnvironmentVariable(named = "COT_REVISION_ERP_SYNC_03A_ENABLED", matches = "true")
class RevisionComercialErpLocalIntegrationTest {
    @Test void descargaPaqueteActualVerificadoDesdeErp() throws Exception {
        Properties local = new Properties();
        Path localFile = Path.of(".env.local");
        if (Files.exists(localFile)) try (var in = Files.newBufferedReader(localFile)) { local.load(in); }
        String url = env("ERP_BASE_URL", local);
        String username = env("ERP_USERNAME", local);
        String password = env("ERP_PASSWORD", local);
        String token = env("ERP_TOKEN", local);
        Assumptions.assumeTrue(!url.isBlank(), "Falta ERP_BASE_URL para la prueba local.");
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build());
        factory.setReadTimeout(Duration.ofSeconds(20));
        var erp = new ErpPedidoClient(RestClient.builder().requestFactory(factory).build(), url, token, username, password);
        var service = new RevisionComercialSyncService(erp, new ObjectMapper().findAndRegisterModules());
        try {
            var paquete = service.actual();
            assertEquals(3, paquete.versionEsquema());
            assertEquals("sha256:" + paquete.huellaSha256(), paquete.referenciaComercial());
            assertTrue(paquete.datosComerciales().path("precios_lista").isArray());
        } catch (com.cyssoftware.effyFlow.service.pedido.ErpCapturaException ex) {
            if (ex.status() == 404) Assumptions.abort("ERP local no tiene una revisión comercial publicada.");
            throw ex;
        }
    }

    private String env(String key, Properties local) {
        return System.getenv().getOrDefault(key, local.getProperty(key, ""));
    }
}
