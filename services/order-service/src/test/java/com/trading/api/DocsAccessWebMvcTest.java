package com.trading.api;

import com.trading.api.config.SecurityConfig;
import com.trading.api.exception.GlobalExceptionHandler;
import com.trading.api.extensions.portfolio.PortfolioController;
import com.trading.api.extensions.portfolio.PortfolioHealthController;
import com.trading.api.extensions.portfolio.PortfolioHistoryService;
import com.trading.api.extensions.portfolio.PortfolioService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** What is reachable without a token: the portfolio health check always, the OpenAPI docs only while app.docs.public is on. */
@WebMvcTest(controllers = {PortfolioController.class, PortfolioHealthController.class}, properties = {
        "app.security.jwt-secret=01234567890123456789012345678901",
        "app.docs.public=false"
})
@AutoConfigureMockMvc
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class DocsAccessWebMvcTest {

    @Autowired
    private MockMvc mockMvc;
    @MockBean
    private PortfolioService portfolioService;
    @MockBean
    private PortfolioHistoryService portfolioHistoryService;
    @MockBean
    private JdbcTemplate jdbcTemplate;

    @Test
    void theOpenApiDocumentNeedsATokenWhenDocsAreNotPublic() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("AUTH-401"));
    }

    @Test
    void theSwaggerUiNeedsATokenWhenDocsAreNotPublic() throws Exception {
        mockMvc.perform(get("/swagger-ui/index.html")).andExpect(status().isUnauthorized());
    }

    @Test
    void theHealthCheckStaysPublicBecauseItsContractSaysSo() throws Exception {
        mockMvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dependencies[0].name").value("postgres"))
                .andExpect(jsonPath("$.status").exists());
    }

    @Test
    void theHealthCheckReportsDegradedWhenADependencyIsNotOk() throws Exception {
        // The mocked JdbcTemplate answers nothing, so the quote freshness cannot be read.
        mockMvc.perform(get("/health"))
                .andExpect(jsonPath("$.status").value("degraded"));
    }

    @Test
    void everyOtherPortfolioRouteStillNeedsAToken() throws Exception {
        mockMvc.perform(get("/api/v1/portfolio/1")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/portfolio/health")).andExpect(status().isUnauthorized()); // the old path is no longer public
    }
}
