package se.oscarwiklund.twlan2.backend.web;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import se.oscarwiklund.twlan2.backend.domain.Account;
import se.oscarwiklund.twlan2.backend.domain.Village;
import se.oscarwiklund.twlan2.backend.domain.World;
import se.oscarwiklund.twlan2.backend.repo.AccountRepository;
import se.oscarwiklund.twlan2.backend.repo.VillageRepository;
import se.oscarwiklund.twlan2.backend.service.AuthService;
import se.oscarwiklund.twlan2.backend.service.WorldService;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

// Several tabs = concurrent requests. Reading the village state must not wait for rows another transaction holds:
// it used to save every own village, so two tabs on different villages deadlocked and one got a 500. And the visit
// written on every request must not fail the request when the account row is busy.
// Not @Transactional: the lock holder runs in its own thread and needs committed rows.
@SpringBootTest
class ConcurrentRequestsTest {

    @Autowired WorldService worlds;
    @Autowired AccountRepository accounts;
    @Autowired VillageRepository villages;
    @Autowired GameFacade facade;
    @Autowired AuthService auth;
    @Autowired PlatformTransactionManager txManager;

    @AfterEach
    void clearContext() {
        AccountContext.clear();
        WorldContext.clear();
    }

    private Account account(String name) {
        Account a = new Account();
        a.setUsername(name + "-" + System.nanoTime());
        a.setPasswordHash("x");
        return accounts.save(a);
    }

    // Runs `body` while another thread's transaction holds the row lock `lock` takes.
    private void whileLocked(Consumer<TransactionTemplate> lock, Runnable body) throws InterruptedException {
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        TransactionTemplate tx = new TransactionTemplate(txManager);
        Thread holder = new Thread(() -> tx.executeWithoutResult(st -> {
            lock.accept(tx);
            locked.countDown();
            try {
                release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        holder.start();
        try {
            assertTrue(locked.await(10, TimeUnit.SECONDS), "lock holder did not start");
            body.run();
        } finally {
            release.countDown();
            holder.join(30_000);
        }
    }

    @Test
    void villageStateDoesNotWaitForAnotherOwnVillage() throws InterruptedException {
        World world = worlds.createWorld("concurrent-village-test", 1);
        Account me = account("me");
        Village home = worlds.joinWorld(world, me);
        Village second = worlds.joinWorld(world, account("former-owner"));
        second.setOwner(me);
        villages.save(second);

        whileLocked(tx -> {
            Village v = villages.findById(second.getId()).orElseThrow();
            v.setWood(v.getWood() + 1); // e.g. an order in the other tab
            villages.saveAndFlush(v);
        }, () -> assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            WorldContext.set(world.getId());
            AccountContext.set(me);
            try {
                assertEquals(2, facade.toDto(home).myVillages().size());
            } finally {
                AccountContext.clear();
                WorldContext.clear();
            }
        }));
    }
}
