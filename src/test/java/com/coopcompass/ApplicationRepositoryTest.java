package com.coopcompass;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public final class ApplicationRepositoryTest {
    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("coop-compass-test-");
        Path dataFile = directory.resolve("applications.tsv");
        ApplicationRepository repository = new ApplicationRepository(dataFile);
        Application created = repository.create(Map.of(
                "company", "Acme", "role", "Software Developer", "status", "APPLIED",
                "skills", "java, REST APIs, PostgreSQL", "deadline", "2026-10-01"));

        assert created.id() == 1;
        assert repository.dashboard().total() == 1;
        assert repository.updateStatus(created.id(), "INTERVIEW").orElseThrow().status() == Application.Status.INTERVIEW;

        ApplicationRepository reloaded = new ApplicationRepository(dataFile);
        assert reloaded.list().size() == 1;
        assert reloaded.list().getFirst().skills().size() == 3;
        assert reloaded.dashboard().responseRate() == 100;
        assert reloaded.delete(created.id());
        System.out.println("ApplicationRepositoryTest passed");
    }
}
