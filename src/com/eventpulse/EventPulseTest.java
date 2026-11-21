package com.eventpulse;

import java.nio.file.Files;
import java.nio.file.Path;

public class EventPulseTest {
    public static void main(String[] args) throws Exception {
        Path tmpDb = Files.createTempFile("eventpulse_test_", ".db");
        tmpDb.toFile().deleteOnExit();

        try (BoxOfficeStore store = new BoxOfficeStore(tmpDb.toString())) {
            // 1. User Alpha locks VIP seat A1
            String holdAlpha = store.holdSeat("A1", "Bharath", "sess-alpha", 60_000L);
            assert holdAlpha.contains("\"ok\":true") : "User Alpha must lock A1";

            // 2. Concurrent User Beta attempting to lock A1 must be rejected
            String holdBeta = store.holdSeat("A1", "Vikram", "sess-beta", 60_000L);
            assert holdBeta.contains("\"ok\":false") : "Concurrent lock on held seat A1 must fail";

            // 3. User Beta cannot confirm a booking without owning the hold token
            String hijackAttempt = store.confirmBooking("sess-beta", "Vikram", "vikram@test.com");
            assert hijackAttempt.contains("\"ok\":false") : "Session without valid hold cannot confirm booking";

            // 4. TTL Expiry Test: Lock seat A2 for 50ms, wait 80ms, verify automatic release
            assert store.holdSeat("A2", "TempUser", "sess-temp", 50L).contains("\"ok\":true");
            Thread.sleep(85L);
            int expired = store.reclaimExpiredHolds();
            assert expired >= 1 : "Expired hold on A2 must be reclaimed by TTL reaper";
            assert store.holdSeat("A2", "Vikram", "sess-beta", 60_000L).contains("\"ok\":true") : "Released seat A2 must be lockable again";

            // 5. Confirm User Alpha's VIP seat A1 (2500 + 18% GST 450 = 2950.00)
            String receipt = store.confirmBooking("sess-alpha", "Bharath", "bharath@test.com");
            assert receipt.contains("\"ok\":true") : "Confirming valid hold must succeed";
            assert receipt.contains("\"total\":2950.00") : "VIP seat total with 18% GST must equal 2950.00: " + receipt;
        }

        System.out.println("ALL EVENTPULSE ASSERTION TESTS PASSED (Atomic Seat Lock, Session Ownership, TTL Hold Expiry, 18% GST Ticket)");
    }
}
