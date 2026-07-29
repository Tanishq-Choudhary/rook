package dev.rook.server.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.rook.core.error.ErrorCode;
import dev.rook.core.error.RookException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@WebMvcTest(ApiExceptionHandlerTest.Throwing.class)
@Import({ApiExceptionHandler.class, ApiExceptionHandlerTest.Throwing.class})
class ApiExceptionHandlerTest {

    @RestController
    static class Throwing {

        @GetMapping("/missing")
        String missing() {
            throw RookException.notFound("job", "42");
        }

        @GetMapping("/invalid")
        String invalid() {
            throw RookException.invalid("bad input");
        }

        @GetMapping("/budget")
        String budget() {
            throw new RookException(ErrorCode.BUDGET_EXCEEDED, "monthly cap reached");
        }

        @GetMapping("/boom")
        String boom() {
            throw new IllegalStateException("secret internals");
        }
    }

    @Autowired MockMvc mvc;

    @Test
    void notFoundMapsTo404WithProblemBody() throws Exception {
        mvc.perform(get("/missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("NOT_FOUND"))
                .andExpect(jsonPath("$.detail").value("job 42 not found"));
    }

    @Test
    void invalidMapsTo400() throws Exception {
        mvc.perform(get("/invalid")).andExpect(status().isBadRequest());
    }

    @Test
    void budgetExceededMapsTo402() throws Exception {
        mvc.perform(get("/budget")).andExpect(status().isPaymentRequired());
    }

    @Test
    void unknownRoutesReturnNotFoundNotServerError() throws Exception {
        mvc.perform(get("/no-such-route")).andExpect(status().isNotFound());
    }

    @Test
    void wrongMethodReturns405() throws Exception {
        mvc.perform(post("/missing")).andExpect(status().isMethodNotAllowed());
    }

    @Test
    void unexpectedFailuresDoNotLeakInternals() throws Exception {
        mvc.perform(get("/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.title").value("INTERNAL"))
                .andExpect(
                        jsonPath("$.detail")
                                .value(
                                        org.hamcrest.Matchers.not(
                                                org.hamcrest.Matchers.containsString("secret"))));
    }
}
