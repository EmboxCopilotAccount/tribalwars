package se.oscarwiklund.twlan2.backend.service;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import se.oscarwiklund.twlan2.backend.domain.*;
import se.oscarwiklund.twlan2.backend.repo.AccountRepository;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

// A player who stays logged in ("Stay logged in" keeps the token) is still playing: using the token counts as a visit,
// so the abandonment sweep leaves their villages alone. Only a token nobody uses lets them turn barbarian.
@SpringBootTest
@Transactional
class AbandonmentLastSeenTest {

    @Autowired AuthService auth;
    @Autowired WorldService worlds;
    @Autowired AccountRepository accounts;
    @Autowired AbandonmentService abandonment;
    @Autowired EntityManager em;

    private record Player(Account account, String token, Village village) {}

    private Player playerLastLoggedIn(World world, String name, Duration ago) {
        AuthService.Login login = auth.register(name + System.nanoTime() % 100000, "secret1");
        Village v = worlds.joinWorld(world, login.account());
        accounts.touchLastLogin(login.account().getId(), Instant.now().minus(ago));
        em.refresh(login.account());
        return new Player(login.account(), login.token(), v);
    }

    @Test
    void playingWithAKeptTokenKeepsTheVillages() {
        World world = worlds.createWorld("lastseen-test", 1);
        world.getSettings().put("leftVillagesGrow", "5000");
        Player playing = playerLastLoggedIn(world, "stays", Duration.ofDays(10));
        Player gone = playerLastLoggedIn(world, "gone", Duration.ofDays(10));

        assertTrue(auth.resolve(playing.token()).isPresent());
        em.refresh(playing.account());
        assertTrue(Duration.between(playing.account().getLastLoginAt(), Instant.now()).toSeconds() < 30);

        abandonment.sweep();
        em.flush(); // (refresh would otherwise throw the unflushed sweep away)
        em.refresh(playing.village());
        em.refresh(gone.village());
        assertEquals(OwnerType.PLAYER, playing.village().getOwnerType());
        assertEquals(playing.account().getId(), playing.village().getOwner().getId());
        assertEquals(OwnerType.BARBARIAN, gone.village().getOwnerType());
    }
}
