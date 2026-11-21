package com.eventpulse;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

public class BoxOfficeStore implements AutoCloseable {
    private final Connection conn;

    public BoxOfficeStore(String dbPath) throws SQLException {
        this.conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
        initSchema();
        seedInitialData();
    }

    private void initSchema() throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL;");
            st.execute("PRAGMA synchronous=NORMAL;");
            st.execute("PRAGMA busy_timeout=5000;");
            st.execute("""
                CREATE TABLE IF NOT EXISTS seats (
                    seat_code TEXT PRIMARY KEY,
                    row_label TEXT NOT NULL,
                    tier TEXT NOT NULL,
                    price REAL NOT NULL,
                    status TEXT NOT NULL DEFAULT 'AVAILABLE',
                    hold_token TEXT DEFAULT '',
                    holder_name TEXT DEFAULT '',
                    hold_expires_at_ms INTEGER NOT NULL DEFAULT 0,
                    booking_code TEXT DEFAULT ''
                )
            """);
            st.execute("""
                CREATE TABLE IF NOT EXISTS bookings (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    booking_code TEXT NOT NULL UNIQUE,
                    buyer_name TEXT NOT NULL,
                    buyer_email TEXT NOT NULL,
                    seat_list TEXT NOT NULL,
                    seat_count INTEGER NOT NULL,
                    subtotal REAL NOT NULL,
                    gst REAL NOT NULL,
                    total_amount REAL NOT NULL,
                    booked_at_ms INTEGER NOT NULL
                )
            """);
            st.execute("CREATE INDEX IF NOT EXISTS idx_seats_hold ON seats(status, hold_expires_at_ms);");
        }
    }

    private void seedInitialData() throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM seats")) {
            if (rs.next() && rs.getInt(1) > 0) {
                return;
            }
        }

        Object[][] tiers = {
            {"A", "VIP Front", 2500.0},
            {"B", "Platinum", 1500.0},
            {"C", "Gold", 900.0},
            {"D", "Silver", 500.0}
        };

        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO seats (seat_code, row_label, tier, price, status) VALUES (?, ?, ?, ?, 'AVAILABLE')")) {
            for (Object[] t : tiers) {
                String row = (String) t[0];
                String tier = (String) t[1];
                double price = (Double) t[2];
                for (int col = 1; col <= 6; col++) {
                    ps.setString(1, row + col);
                    ps.setString(2, row);
                    ps.setString(3, tier);
                    ps.setDouble(4, price);
                    ps.addBatch();
                }
            }
            ps.executeBatch();
        }

        // Seed 1 confirmed booking (A3, A4) and 1 live 90-second hold (B2)
        holdSeat("A3", "Ananya R", "seed-buyer-1", 60_000L);
        holdSeat("A4", "Ananya R", "seed-buyer-1", 60_000L);
        confirmBooking("seed-buyer-1", "Ananya R", "ananya@example.com");
        holdSeat("B2", "Flash Fan #42", "seed-hold-live", 90_000L);
    }

    public synchronized int reclaimExpiredHolds() throws SQLException {
        long now = System.currentTimeMillis();
        try (PreparedStatement ps = conn.prepareStatement(
                """
                UPDATE seats
                SET status = 'AVAILABLE', hold_token = '', holder_name = '', hold_expires_at_ms = 0
                WHERE status = 'HELD' AND hold_expires_at_ms <= ?
                """)) {
            ps.setLong(1, now);
            return ps.executeUpdate();
        }
    }

    public synchronized String holdSeat(String rawSeatCode, String holderName, String sessionToken, long ttlMs) throws SQLException {
        reclaimExpiredHolds();
        String seatCode = rawSeatCode == null ? "" : rawSeatCode.trim().toUpperCase();
        String token = (sessionToken == null || sessionToken.isBlank()) ? "anon-" + System.nanoTime() : sessionToken.trim();
        String name = (holderName == null || holderName.isBlank()) ? "Guest Buyer" : holderName.trim();
        long duration = Math.max(40L, ttlMs);

        conn.setAutoCommit(false);
        try {
            String status;
            String existingToken;
            double price;
            String tier;

            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT status, hold_token, price, tier FROM seats WHERE seat_code = ?")) {
                ps.setString(1, seatCode);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        conn.rollback();
                        return "{\"ok\":false,\"error\":\"Seat " + JsonUtil.escape(seatCode) + " does not exist\"}";
                    }
                    status = rs.getString("status");
                    existingToken = rs.getString("hold_token");
                    price = rs.getDouble("price");
                    tier = rs.getString("tier");
                }
            }

            if ("BOOKED".equals(status)) {
                conn.rollback();
                return "{\"ok\":false,\"error\":\"Seat " + JsonUtil.escape(seatCode) + " is already SOLD\"}";
            }
            if ("HELD".equals(status) && !token.equals(existingToken)) {
                conn.rollback();
                return "{\"ok\":false,\"error\":\"Seat " + JsonUtil.escape(seatCode) + " is locked by another buyer right now\"}";
            }

            // Cap max 4 held seats per session
            try (PreparedStatement cnt = conn.prepareStatement(
                    "SELECT COUNT(*) FROM seats WHERE status = 'HELD' AND hold_token = ? AND seat_code <> ?")) {
                cnt.setString(1, token);
                cnt.setString(2, seatCode);
                try (ResultSet rs = cnt.executeQuery()) {
                    if (rs.next() && rs.getInt(1) >= 4) {
                        conn.rollback();
                        return "{\"ok\":false,\"error\":\"Maximum 4 seats can be held per transaction\"}";
                    }
                }
            }

            long expiresAt = System.currentTimeMillis() + duration;
            try (PreparedStatement upd = conn.prepareStatement(
                    "UPDATE seats SET status = 'HELD', hold_token = ?, holder_name = ?, hold_expires_at_ms = ? WHERE seat_code = ?")) {
                upd.setString(1, token);
                upd.setString(2, name);
                upd.setLong(3, expiresAt);
                upd.setString(4, seatCode);
                upd.executeUpdate();
            }

            conn.commit();
            return String.format(Locale.US,
                "{\"ok\":true,\"seatCode\":\"%s\",\"tier\":\"%s\",\"price\":%.2f,\"expiresAtMs\":%d}",
                JsonUtil.escape(seatCode), JsonUtil.escape(tier), price, expiresAt
            );
        } catch (SQLException e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(true);
        }
    }

    public synchronized String releaseSeat(String rawSeatCode, String sessionToken) throws SQLException {
        reclaimExpiredHolds();
        String seatCode = rawSeatCode == null ? "" : rawSeatCode.trim().toUpperCase();
        String token = sessionToken == null ? "" : sessionToken.trim();

        try (PreparedStatement ps = conn.prepareStatement(
                """
                UPDATE seats
                SET status = 'AVAILABLE', hold_token = '', holder_name = '', hold_expires_at_ms = 0
                WHERE seat_code = ? AND status = 'HELD' AND hold_token = ?
                """)) {
            ps.setString(1, seatCode);
            ps.setString(2, token);
            int updated = ps.executeUpdate();
            if (updated == 0) {
                return "{\"ok\":false,\"error\":\"No active hold owned by your session on seat " + JsonUtil.escape(seatCode) + "\"}";
            }
            return "{\"ok\":true,\"seatCode\":\"" + JsonUtil.escape(seatCode) + "\"}";
        }
    }

    public synchronized String confirmBooking(String sessionToken, String buyerName, String buyerEmail) throws SQLException {
        reclaimExpiredHolds();
        String token = sessionToken == null ? "" : sessionToken.trim();
        String name = (buyerName == null || buyerName.isBlank()) ? "Verified Fan" : buyerName.trim();
        String email = (buyerEmail == null || buyerEmail.isBlank()) ? "fan@eventpulse.io" : buyerEmail.trim();

        conn.setAutoCommit(false);
        try {
            List<String> heldSeats = new ArrayList<>();
            double subtotal = 0.0;

            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT seat_code, price FROM seats WHERE status = 'HELD' AND hold_token = ? ORDER BY seat_code ASC")) {
                ps.setString(1, token);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        heldSeats.add(rs.getString("seat_code"));
                        subtotal += rs.getDouble("price");
                    }
                }
            }

            if (heldSeats.isEmpty()) {
                conn.rollback();
                return "{\"ok\":false,\"error\":\"Your seat hold expired or no seats are selected\"}";
            }

            double gst = Math.round(subtotal * 0.18 * 100.0) / 100.0;
            double total = Math.round((subtotal + gst) * 100.0) / 100.0;
            String seatCsv = String.join(", ", heldSeats);
            long now = System.currentTimeMillis();

            long bookingId;
            String tempCode = "TMP-" + System.nanoTime();
            try (PreparedStatement ins = conn.prepareStatement(
                    """
                    INSERT INTO bookings (booking_code, buyer_name, buyer_email, seat_list, seat_count, subtotal, gst, total_amount, booked_at_ms)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS)) {
                ins.setString(1, tempCode);
                ins.setString(2, name);
                ins.setString(3, email);
                ins.setString(4, seatCsv);
                ins.setInt(5, heldSeats.size());
                ins.setDouble(6, subtotal);
                ins.setDouble(7, gst);
                ins.setDouble(8, total);
                ins.setLong(9, now);
                ins.executeUpdate();
                try (ResultSet keys = ins.getGeneratedKeys()) {
                    bookingId = keys.next() ? keys.getLong(1) : 1L;
                }
            }

            String bookingCode = String.format(Locale.US, "TKT-%04d", 5000 + bookingId);
            try (PreparedStatement updB = conn.prepareStatement("UPDATE bookings SET booking_code = ? WHERE id = ?")) {
                updB.setString(1, bookingCode);
                updB.setLong(2, bookingId);
                updB.executeUpdate();
            }

            try (PreparedStatement updS = conn.prepareStatement(
                    """
                    UPDATE seats
                    SET status = 'BOOKED', booking_code = ?, holder_name = ?, hold_token = '', hold_expires_at_ms = 0
                    WHERE status = 'HELD' AND hold_token = ?
                    """)) {
                updS.setString(1, bookingCode);
                updS.setString(2, name);
                updS.setString(3, token);
                updS.executeUpdate();
            }

            conn.commit();
            return String.format(Locale.US,
                "{\"ok\":true,\"ticket\":{\"bookingCode\":\"%s\",\"buyerName\":\"%s\",\"seats\":\"%s\",\"seatCount\":%d,\"subtotal\":%.2f,\"gst\":%.2f,\"total\":%.2f}}",
                bookingCode, JsonUtil.escape(name), JsonUtil.escape(seatCsv), heldSeats.size(), subtotal, gst, total
            );
        } catch (SQLException e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(true);
        }
    }

    public synchronized String simulateFlashSaleRush() throws SQLException {
        reclaimExpiredHolds();
        List<String> openSeats = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT seat_code FROM seats WHERE status = 'AVAILABLE'")) {
            while (rs.next()) {
                openSeats.add(rs.getString("seat_code"));
            }
        }
        if (openSeats.isEmpty()) {
            return "{\"ok\":false,\"error\":\"Arena is 100% sold out or held!\"}";
        }

        int acted = 0;
        for (int i = 0; i < Math.min(3, openSeats.size()); i++) {
            int pickIdx = ThreadLocalRandom.current().nextInt(openSeats.size());
            String chosen = openSeats.remove(pickIdx);
            String botToken = "flash-bot-" + System.nanoTime();
            if (i == 0) {
                holdSeat(chosen, "Flash Buyer #" + ThreadLocalRandom.current().nextInt(100, 999), botToken, 30_000L);
                confirmBooking(botToken, "Flash Buyer #" + ThreadLocalRandom.current().nextInt(100, 999), "flash@fan.in");
            } else {
                holdSeat(chosen, "Queue User #" + ThreadLocalRandom.current().nextInt(100, 999), botToken, 20_000L);
            }
            acted++;
        }
        return "{\"ok\":true,\"simulatedActions\":" + acted + "}";
    }

    public synchronized String getDashboardStateJson() throws SQLException {
        reclaimExpiredHolds();
        int totalSeats = 0, available = 0, held = 0, booked = 0;
        List<String> seatJsons = new ArrayList<>();

        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM seats ORDER BY seat_code ASC")) {
            while (rs.next()) {
                totalSeats++;
                String status = rs.getString("status");
                if ("AVAILABLE".equals(status)) available++;
                else if ("HELD".equals(status)) held++;
                else if ("BOOKED".equals(status)) booked++;

                seatJsons.add(String.format(Locale.US,
                    "{\"seatCode\":\"%s\",\"row\":\"%s\",\"tier\":\"%s\",\"price\":%.2f,\"status\":\"%s\",\"holdToken\":\"%s\",\"holderName\":\"%s\",\"holdExpiresAtMs\":%d,\"bookingCode\":\"%s\"}",
                    JsonUtil.escape(rs.getString("seat_code")),
                    JsonUtil.escape(rs.getString("row_label")),
                    JsonUtil.escape(rs.getString("tier")),
                    rs.getDouble("price"),
                    JsonUtil.escape(status),
                    JsonUtil.escape(rs.getString("hold_token")),
                    JsonUtil.escape(rs.getString("holder_name")),
                    rs.getLong("hold_expires_at_ms"),
                    JsonUtil.escape(rs.getString("booking_code"))
                ));
            }
        }

        double boxOfficeRevenue = 0.0;
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COALESCE(SUM(total_amount), 0.0) FROM bookings")) {
            if (rs.next()) boxOfficeRevenue = rs.getDouble(1);
        }

        List<String> bookingJsons = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT booking_code, buyer_name, seat_list, total_amount FROM bookings ORDER BY id DESC LIMIT 6")) {
            while (rs.next()) {
                bookingJsons.add(String.format(Locale.US,
                    "{\"bookingCode\":\"%s\",\"buyerName\":\"%s\",\"seats\":\"%s\",\"total\":%.2f}",
                    JsonUtil.escape(rs.getString("booking_code")),
                    JsonUtil.escape(rs.getString("buyer_name")),
                    JsonUtil.escape(rs.getString("seat_list")),
                    rs.getDouble("total_amount")
                ));
            }
        }

        return String.format(Locale.US,
            "{\"serverNowMs\":%d,\"metrics\":{\"totalSeats\":%d,\"available\":%d,\"held\":%d,\"booked\":%d,\"revenue\":%.2f},\"seats\":[%s],\"recentBookings\":[%s]}",
            System.currentTimeMillis(), totalSeats, available, held, booked, boxOfficeRevenue,
            String.join(",", seatJsons),
            String.join(",", bookingJsons)
        );
    }

    @Override
    public void close() throws SQLException {
        if (conn != null && !conn.isClosed()) {
            conn.close();
        }
    }
}
