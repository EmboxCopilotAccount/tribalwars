package se.oscarwiklund.twlan2.backend.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import se.oscarwiklund.twlan2.backend.service.AuthService;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// A request the interceptor refuses (here a non-admin on /api/admin) must not leave its account on the thread:
// Spring skips afterCompletion then, and the next request without a token used to run as that account.
// MockMvc runs both requests on this one thread, like a pooled Tomcat thread serving them one after the other.
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class RefusedRequestContextTest {

    @Autowired MockMvc mvc;
    @Autowired AuthService auth;

    @Test
    void aRefusedRequestLeavesNoLoginBehind() throws Exception {
        AuthService.Login first = auth.register("refusedA" + System.nanoTime() % 100000, "secret1");
        AuthService.Login second = auth.register("refusedB" + System.nanoTime() % 100000, "secret1");
        String token = !first.account().isAdmin() ? first.token() : second.token(); // the first real account may be admin

        mvc.perform(get("/api/admin/worlds").header("Authorization", "Bearer " + token)).andExpect(status().isForbidden());
        mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer nonsense")).andExpect(status().isUnauthorized());
    }
}
