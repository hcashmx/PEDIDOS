package com.cyssoftware.effyFlow.repository;

import static org.junit.jupiter.api.Assertions.*;

import com.cyssoftware.effyFlow.service.cotizaciones.CotizacionSyncRepositoryModels.DeliveryPage;
import com.cyssoftware.effyFlow.service.cotizaciones.CotizacionSyncRepositoryModels.ErpEvent;
import com.cyssoftware.effyFlow.service.cotizaciones.CotizacionSyncRepositoryModels.SessionRow;
import com.cyssoftware.effyFlow.service.cotizaciones.CotizacionSyncRepositoryModels.SnapshotDocument;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** Opt-in integration coverage against a disposable PostgreSQL 17 database. */
class CotizacionSyncPostgresIT {
    private static String url;
    private static String user;
    private static String password;
    private static JdbcTemplate sql;
    private static CotizacionSyncRepository repository;
    private static TransactionTemplate tx;
    private final List<UUID> sessions = new ArrayList<>();

    @BeforeAll
    static void connectOnlyWhenExplicitlyConfigured() {
        url = System.getenv("COTIZACIONES_TEST_JDBC_URL");
        user = System.getenv("COTIZACIONES_TEST_DB_USER");
        password = System.getenv("COTIZACIONES_TEST_DB_PASSWORD");
        org.junit.jupiter.api.Assumptions.assumeTrue(url != null && user != null && password != null,
                "Set COTIZACIONES_TEST_JDBC_URL/DB_USER/DB_PASSWORD to opt in to disposable PostgreSQL tests");
        DriverManagerDataSource dataSource = new DriverManagerDataSource(url, user, password);
        sql = new JdbcTemplate(dataSource);
        repository = new CotizacionSyncRepository(new NamedParameterJdbcTemplate(dataSource));
        tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        assertEquals("sync_cotizacion_event_ledger", sql.queryForObject(
                "SELECT to_regclass('public.sync_cotizacion_event_ledger')::text", String.class));
    }

    @AfterEach
    void deleteOnlyFixturesCreatedByThisTest() {
        for (UUID id : sessions) {
            UUID device = sql.query("SELECT device_id FROM public.sync_cotizacion_sesion WHERE session_id=?",
                    rs -> rs.next() ? rs.getObject(1, UUID.class) : null, id);
            sql.update("DELETE FROM public.sync_cotizacion_sesion WHERE session_id=?", id);
            if (device != null) sql.update("DELETE FROM public.sync_cotizacion_generation WHERE device_id=?", device);
        }
    }

    @Test
    void bootstrapSnapshotAcrossPagesAndEmptySnapshotTerminalAck() {
        UUID owner = UUID.randomUUID();
        SessionRow session = session();
        bootstrap(session, owner, 20);
        UUID one = UUID.randomUUID();
        UUID two = UUID.randomUUID();
        page(session, owner, 0, 100, true, List.of(snapshot(one, 801, 1)));
        page(session, owner, 100, 900, false, List.of(snapshot(two, 802, 3)));
        run(() -> repository.materializeSnapshot(session.sessionId(), owner));

        DeliveryPage first = call(() -> repository.offer(session.sessionId(), "SNAPSHOT", 0, 1));
        assertEquals(1, first.rows().size());
        call(() -> repository.acknowledge(session.sessionId(), 1));
        DeliveryPage rest = call(() -> repository.offer(session.sessionId(), "SNAPSHOT", 1, 5));
        assertEquals(List.of("SNAPSHOT", "SNAPSHOT_END"), rest.rows().stream()
                .map(row -> row.itemType()).toList());
        call(() -> repository.acknowledge(session.sessionId(), 3));
        assertEquals("ACTIVA", repository.findById(session.sessionId()).state());

        SessionRow empty = session();
        UUID emptyOwner = UUID.randomUUID();
        bootstrap(empty, emptyOwner, 0);
        page(empty, emptyOwner, 0, 0, false, List.of());
        run(() -> repository.materializeSnapshot(empty.sessionId(), emptyOwner));
        DeliveryPage terminal = call(() -> repository.offer(empty.sessionId(), "SNAPSHOT", 0, 1));
        assertEquals("SNAPSHOT_END", terminal.rows().getFirst().itemType());
        call(() -> repository.acknowledge(empty.sessionId(), 1));
        assertEquals("ACTIVA", repository.findById(empty.sessionId()).state());
    }

    @Test
    void incrementalCursorLedgerReplayAfterInboxCompactionAndVersionConflictRollback() {
        UUID owner = UUID.randomUUID();
        UUID document = UUID.randomUUID();
        SessionRow session = active(owner, 40, List.of(snapshot(document, 901, 1)));
        ErpEvent event = event(41, document, 901, "EN_REVISION", 2, true, 7, HASH_A);
        assertTrue(ingest(session, owner, 40, event, false, 100, 1_000_000));
        assertEquals(41L, repository.findById(session.sessionId()).erpCursor());
        call(() -> sql.update("DELETE FROM public.sync_cotizacion_event_inbox WHERE session_id=? AND event_id=41",
                session.sessionId()));
        call(() -> sql.update("UPDATE public.sync_cotizacion_sesion SET erp_cursor=40 WHERE session_id=?",
                session.sessionId()));
        assertTrue(ingest(session, owner, 40, event, false, 100, 1_000_000));
        assertEquals(1L, count("SELECT COUNT(*) FROM public.sync_cotizacion_event_ledger WHERE session_id=? AND event_id=41",
                session.sessionId()));
        assertEquals(1L, count("SELECT COUNT(*) FROM public.sync_cotizacion_delivery WHERE session_id=? AND event_id=41",
                session.sessionId()));

        ErpEvent contradiction = event(42, document, 901, "APROBADA", 2, true, 7, HASH_B);
        assertThrows(ResponseStatusException.class,
                () -> ingest(session, owner, 41, contradiction, false, 100, 1_000_000));
        assertEquals(41L, repository.findById(session.sessionId()).erpCursor());
        assertEquals(0L, count("SELECT COUNT(*) FROM public.sync_cotizacion_event_ledger WHERE session_id=? AND event_id=42",
                session.sessionId()));
    }

    @Test
    void tombstonePreventsOlderVisibleVersionFromResurrectingDocument() {
        UUID owner = UUID.randomUUID();
        UUID document = UUID.randomUUID();
        SessionRow session = active(owner, 100, List.of());
        assertTrue(ingest(session, owner, 100,
                event(101, document, 1001, null, 5, false, null, HASH_A), false, 100, 1_000_000));
        assertTrue(ingest(session, owner, 101,
                event(102, document, 1001, "BORRADOR", 4, true, 7, HASH_B), false, 100, 1_000_000));
        assertEquals(1L, count("SELECT COUNT(*) FROM public.sync_cotizacion_projection WHERE session_id=? AND cotizacion_uuid=? AND NOT visible AND estado IS NULL AND agente_id IS NULL AND version=5",
                session.sessionId(), document));
        assertEquals(1L, count("SELECT COUNT(*) FROM public.sync_cotizacion_tombstone WHERE session_id=? AND cotizacion_uuid=? AND version=5",
                session.sessionId(), document));
    }

    @Test
    void ackRejectsUnofferedAndNonContiguousSequenceAndAcceptsRepeat() {
        UUID owner = UUID.randomUUID();
        SessionRow session = active(owner, 0, List.of());
        DeliveryPage offered = call(() -> repository.offer(session.sessionId(), "SNAPSHOT", 1, 1));
        assertEquals(0, offered.rows().size());
        assertThrows(ResponseStatusException.class,
                () -> call(() -> repository.acknowledge(session.sessionId(), 2)));

        // Add two fixture deliveries with a deliberate missing sequence to exercise the server-side gap guard.
        UUID id = session.sessionId();
        call(() -> sql.update("INSERT INTO sync_cotizacion_delivery(session_id,delivery_sequence,item_type,event_id,cotizacion_uuid,erp_id,estado,version,event_type,visible) VALUES (?,2,'EVENT',2001,?,1002,'A',1,'E',true)",
                id, UUID.randomUUID()));
        call(() -> sql.update("INSERT INTO sync_cotizacion_delivery(session_id,delivery_sequence,item_type,event_id,cotizacion_uuid,erp_id,estado,version,event_type,visible) VALUES (?,4,'EVENT',2002,?,1003,'B',1,'E',true)",
                id, UUID.randomUUID()));
        call(() -> sql.update("UPDATE sync_cotizacion_sesion SET next_delivery_sequence=5 WHERE session_id=?", id));
        call(() -> repository.offer(id, "EVENT", 1, 10));
        assertThrows(ResponseStatusException.class, () -> call(() -> repository.acknowledge(id, 4)));
        call(() -> repository.acknowledge(id, 2));
        assertEquals(2L, call(() -> repository.acknowledge(id, 2)));
    }

    @Test
    void competingWorkersAndExpiredLeaseUseIndependentConnections() throws Exception {
        SessionRow session = session();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> claimAtSameTime(session.sessionId(), first, ready, start));
            var b = pool.submit(() -> claimAtSameTime(session.sessionId(), second, ready, start));
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            assertNotEquals(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS));
        }
        call(() -> sql.update("UPDATE sync_cotizacion_sesion SET lease_until=CURRENT_TIMESTAMP-INTERVAL '1 second' WHERE session_id=?",
                session.sessionId()));
        assertTrue(call(() -> repository.claimWorker(session.sessionId(), second, Instant.now().plusSeconds(60))));
    }

    @Test
    void revocationCommittedWhileOfferWaitsForSessionLockPreventsNewOffer() throws Exception {
        UUID owner = UUID.randomUUID();
        SessionRow session = active(owner, 0, List.of());
        try (Connection revoker = DriverManager.getConnection(url, user, password)) {
            revoker.setAutoCommit(false);
            try (var statement = revoker.prepareStatement(
                    "UPDATE sync_cotizacion_sesion SET state='REVOCADA' WHERE session_id=?")) {
                statement.setObject(1, session.sessionId());
                assertEquals(1, statement.executeUpdate());
            }
            CountDownLatch started = new CountDownLatch(1);
            try (var pool = Executors.newSingleThreadExecutor()) {
                var response = pool.submit(() -> {
                    started.countDown();
                    return assertThrows(ResponseStatusException.class,
                            () -> call(() -> repository.offer(session.sessionId(), "EVENT", 1, 10)));
                });
                assertTrue(started.await(5, TimeUnit.SECONDS));
                Thread.sleep(200);
                assertFalse(response.isDone(), "offer must wait for the in-flight revocation row lock");
                revoker.commit();
                assertNotNull(response.get(10, TimeUnit.SECONDS));
            } finally {
                if (!revoker.getAutoCommit()) revoker.rollback();
            }
        }
    }

    @Test
    void retentionDeletesAckedPayloadButKeepsLedgerAndAdvancesFloor() {
        UUID owner = UUID.randomUUID();
        UUID document = UUID.randomUUID();
        SessionRow session = active(owner, 5, List.of());
        assertTrue(ingest(session, owner, 5,
                event(6, document, 1100, "ENVIADA", 1, true, 7, HASH_A), false, 100, 1_000_000));
        DeliveryPage event = call(() -> repository.offer(session.sessionId(), "EVENT", 1, 10));
        call(() -> repository.acknowledge(session.sessionId(), event.rows().getLast().deliverySequence()));
        call(() -> sql.update("UPDATE sync_cotizacion_delivery SET created_at=CURRENT_TIMESTAMP-INTERVAL '40 days' WHERE session_id=? AND event_id=6",
                session.sessionId()));
        call(() -> sql.update("UPDATE sync_cotizacion_event_inbox SET received_at=CURRENT_TIMESTAMP-INTERVAL '40 days' WHERE session_id=? AND event_id=6",
                session.sessionId()));
        call(() -> repository.pruneAcknowledgedData(30, 100));
        assertEquals(1L, count("SELECT COUNT(*) FROM sync_cotizacion_event_ledger WHERE session_id=? AND event_id=6",
                session.sessionId()));
        assertEquals(0L, count("SELECT COUNT(*) FROM sync_cotizacion_event_inbox WHERE session_id=? AND event_id=6",
                session.sessionId()));
        assertEquals(0L, count("SELECT COUNT(*) FROM sync_cotizacion_delivery WHERE session_id=? AND event_id=6",
                session.sessionId()));
        assertEquals(3L, sql.queryForObject("SELECT delivery_retention_floor FROM sync_cotizacion_sesion WHERE session_id=?",
                Long.class, session.sessionId()));
    }

    @Test
    void saturatedSessionPausesCursorUntilQueueDrainsWhileOtherSessionContinues() {
        UUID owner = UUID.randomUUID();
        SessionRow saturated = active(owner, 0, List.of());
        UUID otherOwner = UUID.randomUUID();
        SessionRow other = active(otherOwner, 0, List.of());
        UUID doc1 = UUID.randomUUID();
        assertTrue(ingest(saturated, owner, 0,
                event(1, doc1, 1201, "A", 1, true, 7, HASH_A), false, 1, 1_000_000));
        assertFalse(ingest(saturated, owner, 1,
                event(2, UUID.randomUUID(), 1202, "B", 1, true, 7, HASH_B), false, 1, 1_000_000));
        assertEquals(1L, repository.findById(saturated.sessionId()).erpCursor());
        assertTrue(repository.findById(saturated.sessionId()).queueSaturated());

        DeliveryPage queued = call(() -> repository.offer(saturated.sessionId(), "EVENT", 1, 10));
        call(() -> repository.acknowledge(saturated.sessionId(), queued.rows().getLast().deliverySequence()));
        assertFalse(call(() -> repository.queueUsage(saturated.sessionId(), 1, 1_000_000, 0, 0)).saturated());
        assertTrue(ingest(saturated, owner, 1,
                event(2, UUID.randomUUID(), 1202, "B", 1, true, 7, HASH_B), false, 1, 1_000_000));
        assertTrue(ingest(other, otherOwner, 0,
                event(1, UUID.randomUUID(), 1301, "OTHER", 1, true, 8, HASH_A), false, 20, 1_000_000));
        assertEquals(1L, repository.findById(other.sessionId()).erpCursor());
    }

    @Test
    void resetCreatesNewGenerationAndDeliveryRowsStayInsideTheirSession() {
        long userId = 9_999_001L;
        int agentId = 7799;
        UUID device = UUID.randomUUID();
        SessionRow old = call(() -> repository.createSession(userId, agentId, device));
        sessions.add(old.sessionId());
        run(() -> repository.closeForReset(old.sessionId()));
        SessionRow fresh = call(() -> repository.createSession(userId, agentId, device));
        sessions.add(fresh.sessionId());
        assertEquals(1L, old.generation());
        assertEquals(2L, fresh.generation());
        assertEquals("REQUIERE_REINICIO", repository.findById(old.sessionId()).state());

        UUID ownerA = UUID.randomUUID();
        UUID ownerB = UUID.randomUUID();
        SessionRow a = active(ownerA, 0, List.of());
        SessionRow b = active(ownerB, 0, List.of());
        UUID onlyA = UUID.randomUUID();
        UUID onlyB = UUID.randomUUID();
        assertTrue(ingest(a, ownerA, 0, event(1, onlyA, 1401, "A", 1, true, 7, HASH_A), false, 10, 1_000_000));
        assertTrue(ingest(b, ownerB, 0, event(1, onlyB, 1402, "B", 1, true, 8, HASH_B), false, 10, 1_000_000));
        assertEquals(0L, count("SELECT COUNT(*) FROM sync_cotizacion_delivery WHERE session_id=? AND cotizacion_uuid=?",
                a.sessionId(), onlyB));
        assertEquals(0L, count("SELECT COUNT(*) FROM sync_cotizacion_delivery WHERE session_id=? AND cotizacion_uuid=?",
                b.sessionId(), onlyA));
    }

    @Test
    void migratedLegacyGenerationCannotOfferAfterLedgerMigration() {
        String configured = System.getenv("COTIZACIONES_UPGRADE_FIXTURE_SESSION_ID");
        org.junit.jupiter.api.Assumptions.assumeTrue(configured != null,
                "Set COTIZACIONES_UPGRADE_FIXTURE_SESSION_ID only for the dedicated migration fixture database");
        UUID legacySession = UUID.fromString(configured);
        assertEquals("REQUIERE_REINICIO", repository.findById(legacySession).state());
        assertThrows(ResponseStatusException.class,
                () -> call(() -> repository.offer(legacySession, "EVENT", 0, 10)));
    }

    private boolean claimAtSameTime(UUID id, UUID owner, CountDownLatch ready, CountDownLatch start)
            throws InterruptedException {
        ready.countDown();
        assertTrue(start.await(5, TimeUnit.SECONDS));
        return call(() -> repository.claimWorker(id, owner, Instant.now().plusSeconds(60)));
    }

    private SessionRow session() {
        SessionRow row = call(() -> repository.createSession(9_900_000L + sessions.size(), 7700,
                UUID.randomUUID()));
        sessions.add(row.sessionId());
        return row;
    }

    private SessionRow active(UUID owner, long cursor, List<SnapshotDocument> documents) {
        SessionRow row = session();
        bootstrap(row, owner, cursor);
        page(row, owner, 0, 0, false, documents);
        run(() -> repository.materializeSnapshot(row.sessionId(), owner));
        DeliveryPage snapshot = call(() -> repository.offer(row.sessionId(), "SNAPSHOT", 0, 100));
        assertFalse(snapshot.rows().isEmpty());
        call(() -> repository.acknowledge(row.sessionId(), snapshot.rows().getLast().deliverySequence()));
        return row;
    }

    private void bootstrap(SessionRow row, UUID owner, long cursor) {
        assertTrue(call(() -> repository.claimBootstrap(row.sessionId())));
        assertTrue(call(() -> repository.claimWorker(row.sessionId(), owner, Instant.now().plusSeconds(600))));
        run(() -> repository.saveBootstrap(row.sessionId(), owner, cursor, 1_000));
    }

    private void page(SessionRow row, UUID owner, long expected, long next, boolean hasMore,
            List<SnapshotDocument> documents) {
        run(() -> repository.saveSnapshotPage(row.sessionId(), owner, expected, next, hasMore, documents));
    }

    private boolean ingest(SessionRow row, UUID owner, long cursor, ErpEvent event, boolean hasMore,
            long maxEntries, long maxBytes) {
        return call(() -> repository.ingestEventPage(row.sessionId(), owner, cursor, event.eventId(), hasMore,
                List.of(event), Instant.now().plusSeconds(600), true, maxEntries, maxBytes));
    }

    private static SnapshotDocument snapshot(UUID uuid, long erpId, long version) {
        return new SnapshotDocument(uuid, erpId, "BORRADOR", version, 7, Instant.now());
    }

    private static ErpEvent event(long id, UUID uuid, long erpId, String state, long version,
            boolean visible, Integer agent, String hash) {
        return new ErpEvent(id, uuid, erpId, state, version, Instant.now(), "ESTADO", agent, visible, hash);
    }

    private static final String HASH_A = "a".repeat(64);
    private static final String HASH_B = "b".repeat(64);

    private <T> T call(Supplier<T> work) {
        return tx.execute(status -> work.get());
    }

    private void run(Runnable work) {
        tx.executeWithoutResult(status -> work.run());
    }

    private long count(String query, Object... args) {
        return sql.queryForObject(query, Long.class, args);
    }
}
