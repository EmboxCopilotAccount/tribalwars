package se.oscarwiklund.twlan2.backend.web;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;
import se.oscarwiklund.twlan2.backend.domain.Village;

import static org.junit.jupiter.api.Assertions.assertNotNull;

// open-in-view is off, so the village state must be built inside a transaction: a movement's units load lazily.
// The @SpringBootTest tests run in their own transaction and can't notice when this one goes missing.
class GameFacadeTransactionTest {

    @Test
    void villageStateIsBuiltInATransaction() throws NoSuchMethodException {
        assertNotNull(GameFacade.class.getMethod("toDto", Village.class).getAnnotation(Transactional.class));
    }
}
