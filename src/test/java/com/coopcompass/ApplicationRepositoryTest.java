package com.coopcompass;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

public final class ApplicationRepositoryTest {
    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("coop-compass-test-");
        Path dataFile = directory.resolve("applications.tsv");
        Instant now = Instant.parse("2026-09-14T12:00:00Z");
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);
        ApplicationRepository repository = new ApplicationRepository(dataFile, clock);
        Application created = repository.create(Map.of(
                "company", "Acme", "role", "Software Developer", "status", "APPLIED",
                "skills", "java, REST APIs, PostgreSQL", "deadline", "2026-10-01"));

        assert created.id() == 1;
        assert repository.dashboard().total() == 1;
        assert repository.updateStatus(created.id(), "INTERVIEW").orElseThrow().status() == Application.Status.INTERVIEW;

        ApplicationRepository reloaded = new ApplicationRepository(dataFile, clock);
        assert reloaded.list().size() == 1;
        assert reloaded.list().getFirst().skills().size() == 3;
        assert reloaded.dashboard().responseRate() == 100;
        assert reloaded.delete(created.id());

        assert reloaded.list().isEmpty();
        assert reloaded.dashboard().total() == 0;
        List<ApplicationRepository.DeletedApplication> deleted = reloaded.recentlyDeleted();
        assert deleted.size() == 1;
        assert deleted.getFirst().application().id() == created.id();
        assert deleted.getFirst().application().status() == Application.Status.INTERVIEW;
        assert deleted.getFirst().deletedAt().equals(now);

        ApplicationRepository deletedReloaded = new ApplicationRepository(dataFile, clock);
        assert deletedReloaded.list().isEmpty();
        assert deletedReloaded.recentlyDeleted().size() == 1;
        assert deletedReloaded.restore(created.id()).orElseThrow().company().equals("Acme");
        assert deletedReloaded.list().size() == 1;
        assert deletedReloaded.recentlyDeleted().isEmpty();

        assert deletedReloaded.delete(created.id());
        assert deletedReloaded.permanentlyDelete(created.id());
        assert deletedReloaded.recentlyDeleted().isEmpty();

        Application expiring = deletedReloaded.create(Map.of(
                "company", "Orbit", "role", "Backend Intern", "status", "SAVED"));
        assert deletedReloaded.delete(expiring.id());
        Clock afterRetention = Clock.fixed(now.plusSeconds(8L * 24 * 60 * 60), ZoneOffset.UTC);
        ApplicationRepository expiredReloaded = new ApplicationRepository(dataFile, afterRetention);
        assert expiredReloaded.list().isEmpty();
        assert expiredReloaded.recentlyDeleted().isEmpty();
        System.out.println("ApplicationRepositoryTest passed");
    }
}
