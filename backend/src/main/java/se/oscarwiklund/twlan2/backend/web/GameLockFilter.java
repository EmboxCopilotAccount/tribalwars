package se.oscarwiklund.twlan2.backend.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import se.oscarwiklund.twlan2.backend.service.GameLock;

import java.io.IOException;

// Every API request runs under the GameLock, GETs too: the village screens settle and save resources (toDto). Wraps
// the whole request, so the village is loaded after the lock is taken and saved before it is released. The live-update
// stream (SSE) returns its emitter at once, so it does not hold the lock while it stays open.
@Component
public class GameLockFilter extends OncePerRequestFilter {

    private final GameLock gameLock;

    public GameLockFilter(GameLock gameLock) {
        this.gameLock = gameLock;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            gameLock.run(() -> {
                try {
                    chain.doFilter(request, response);
                } catch (IOException | ServletException e) {
                    throw new Wrapped(e);
                }
            });
        } catch (Wrapped w) {
            if (w.getCause() instanceof IOException io) throw io;
            throw (ServletException) w.getCause();
        }
    }

    private static class Wrapped extends RuntimeException {
        Wrapped(Exception cause) { super(cause); }
    }
}
