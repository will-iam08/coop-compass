package com.coopcompass;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

public final class ApplicationRepository {
    private static final Duration RECENTLY_DELETED_RETENTION = Duration.ofDays(7);

    private final Path dataFile;
    private final Clock clock;
    private final Map<Long, Application> applications = new LinkedHashMap<>();
    private final Map<Long, DeletedApplication> recentlyDeleted = new LinkedHashMap<>();
    private final AtomicLong nextId = new AtomicLong(1);

    public ApplicationRepository(Path dataFile) throws IOException {
        this(dataFile, Clock.systemUTC());
    }

    ApplicationRepository(Path dataFile, Clock clock) throws IOException {
        this.dataFile = dataFile.toAbsolutePath().normalize();
        this.clock = clock;
        load();
        purgeExpiredAndSave();
    }

    public synchronized List<Application> list() throws IOException {
        purgeExpiredAndSave();
        return applications.values().stream()
                .sorted(Comparator.comparing(Application::createdAt).reversed())
                .toList();
    }

    public synchronized Application create(Map<String, String> input) throws IOException {
        purgeExpiredAndSave();
        String company = required(input, "company", 80);
        String role = required(input, "role", 100);
        String location = optional(input, "location", 80);
        String source = optional(input, "source", 60);
        String notes = optional(input, "notes", 1000);
        String deadline = deadline(input.getOrDefault("deadline", ""));
        Application.Status status = Application.Status.from(input.get("status"));
        List<String> skills = skills(input.getOrDefault("skills", ""));

        Application application = new Application(nextId.getAndIncrement(), company, role, location, source, status,
                deadline, notes, skills, clock.instant());
        applications.put(application.id(), application);
        try {
            save();
        } catch (IOException exception) {
            applications.remove(application.id());
            throw exception;
        }
        return application;
    }

    public synchronized Optional<Application> updateStatus(long id, String rawStatus) throws IOException {
        purgeExpiredAndSave();
        Application existing = applications.get(id);
        if (existing == null) return Optional.empty();
        Application updated = existing.withStatus(Application.Status.from(rawStatus));
        applications.put(id, updated);
        try {
            save();
        } catch (IOException exception) {
            applications.put(id, existing);
            throw exception;
        }
        return Optional.of(updated);
    }

    /**
     * Changes the status of every requested active application in one save operation.
     * If any ID is not active, nothing is changed.
     */
    public synchronized Optional<List<Application>> updateStatuses(List<Long> ids, String rawStatus) throws IOException {
        purgeExpiredAndSave();
        List<Long> requestedIds = requiredIds(ids);
        Application.Status status = requiredStatus(rawStatus);
        List<Application> originals = activeApplications(requestedIds);
        if (originals == null) return Optional.empty();

        List<Application> updated = originals.stream().map(application -> application.withStatus(status)).toList();
        updated.forEach(application -> applications.put(application.id(), application));
        try {
            save();
        } catch (IOException exception) {
            originals.forEach(application -> applications.put(application.id(), application));
            throw exception;
        }
        return Optional.of(updated);
    }

    public synchronized boolean delete(long id) throws IOException {
        purgeExpiredAndSave();
        Application application = applications.remove(id);
        if (application == null) return false;
        DeletedApplication deleted = new DeletedApplication(application, clock.instant());
        recentlyDeleted.put(id, deleted);
        try {
            save();
        } catch (IOException exception) {
            recentlyDeleted.remove(id);
            applications.put(id, application);
            throw exception;
        }
        return true;
    }

    /**
     * Soft-deletes every requested active application in one save operation.
     * If any ID is not active, nothing is moved to Recently Deleted.
     */
    public synchronized Optional<List<DeletedApplication>> deleteAll(List<Long> ids) throws IOException {
        purgeExpiredAndSave();
        List<Long> requestedIds = requiredIds(ids);
        List<Application> originals = activeApplications(requestedIds);
        if (originals == null) return Optional.empty();

        Instant deletedAt = clock.instant();
        List<DeletedApplication> deleted = originals.stream()
                .map(application -> new DeletedApplication(application, deletedAt))
                .toList();
        originals.forEach(application -> applications.remove(application.id()));
        deleted.forEach(application -> recentlyDeleted.put(application.application().id(), application));
        try {
            save();
        } catch (IOException exception) {
            deleted.forEach(application -> recentlyDeleted.remove(application.application().id()));
            originals.forEach(application -> applications.put(application.id(), application));
            throw exception;
        }
        return Optional.of(deleted);
    }

    public synchronized List<DeletedApplication> recentlyDeleted() throws IOException {
        purgeExpiredAndSave();
        return recentlyDeleted.values().stream()
                .sorted(Comparator.comparing(DeletedApplication::deletedAt).reversed())
                .toList();
    }

    public synchronized Optional<Application> restore(long id) throws IOException {
        purgeExpiredAndSave();
        DeletedApplication deleted = recentlyDeleted.remove(id);
        if (deleted == null) return Optional.empty();
        Application replaced = applications.put(id, deleted.application());
        try {
            save();
        } catch (IOException exception) {
            applications.remove(id);
            if (replaced != null) applications.put(id, replaced);
            recentlyDeleted.put(id, deleted);
            throw exception;
        }
        return Optional.of(deleted.application());
    }

    /**
     * Restores every requested Recently Deleted application in one save operation.
     * If any ID is no longer available, nothing is restored.
     */
    public synchronized Optional<List<Application>> restoreAll(List<Long> ids) throws IOException {
        purgeExpiredAndSave();
        List<Long> requestedIds = requiredIds(ids);
        List<DeletedApplication> originals = deletedApplications(requestedIds);
        if (originals == null || originals.stream().anyMatch(deleted -> applications.containsKey(deleted.application().id()))) {
            return Optional.empty();
        }

        List<Application> restored = originals.stream().map(DeletedApplication::application).toList();
        originals.forEach(deleted -> recentlyDeleted.remove(deleted.application().id()));
        restored.forEach(application -> applications.put(application.id(), application));
        try {
            save();
        } catch (IOException exception) {
            restored.forEach(application -> applications.remove(application.id()));
            originals.forEach(deleted -> recentlyDeleted.put(deleted.application().id(), deleted));
            throw exception;
        }
        return Optional.of(restored);
    }

    public synchronized boolean permanentlyDelete(long id) throws IOException {
        purgeExpiredAndSave();
        DeletedApplication deleted = recentlyDeleted.remove(id);
        if (deleted == null) return false;
        try {
            save();
        } catch (IOException exception) {
            recentlyDeleted.put(id, deleted);
            throw exception;
        }
        return true;
    }

    /**
     * Permanently removes every requested Recently Deleted application in one save operation.
     * If any ID is no longer available, nothing is removed.
     */
    public synchronized Optional<List<DeletedApplication>> permanentlyDeleteAll(List<Long> ids) throws IOException {
        purgeExpiredAndSave();
        List<Long> requestedIds = requiredIds(ids);
        List<DeletedApplication> originals = deletedApplications(requestedIds);
        if (originals == null) return Optional.empty();

        originals.forEach(deleted -> recentlyDeleted.remove(deleted.application().id()));
        try {
            save();
        } catch (IOException exception) {
            originals.forEach(deleted -> recentlyDeleted.put(deleted.application().id(), deleted));
            throw exception;
        }
        return Optional.of(originals);
    }

    public synchronized Dashboard dashboard() throws IOException {
        purgeExpiredAndSave();
        Map<Application.Status, Long> byStatus = new LinkedHashMap<>();
        for (Application.Status status : Application.Status.values()) {
            byStatus.put(status, applications.values().stream().filter(application -> application.status() == status).count());
        }
        long total = applications.size();
        long applied = byStatus.get(Application.Status.APPLIED) + byStatus.get(Application.Status.INTERVIEW)
                + byStatus.get(Application.Status.OFFER) + byStatus.get(Application.Status.REJECTED);
        long responses = byStatus.get(Application.Status.INTERVIEW) + byStatus.get(Application.Status.OFFER)
                + byStatus.get(Application.Status.REJECTED);
        int responseRate = applied == 0 ? 0 : (int) Math.round((responses * 100.0) / applied);
        return new Dashboard(total, byStatus, responseRate);
    }

    private void load() throws IOException {
        if (!Files.exists(dataFile)) return;
        for (String row : Files.readAllLines(dataFile, StandardCharsets.UTF_8)) {
            if (row.isBlank()) continue;
            String[] values = row.split("\\t", -1);
            if (values.length != 10 && values.length != 11) continue;
            try {
                long id = Long.parseLong(values[0]);
                Application application = new Application(id, decode(values[1]), decode(values[2]), decode(values[3]),
                        decode(values[4]), Application.Status.valueOf(values[5]), decode(values[6]), decode(values[7]),
                        skills(decode(values[8])), Instant.parse(values[9]));
                String deletedAt = values.length == 11 ? decode(values[10]) : "";
                if (deletedAt.isBlank()) {
                    recentlyDeleted.remove(id);
                    applications.put(id, application);
                } else {
                    applications.remove(id);
                    recentlyDeleted.put(id, new DeletedApplication(application, Instant.parse(deletedAt)));
                }
                nextId.set(Math.max(nextId.get(), id + 1));
            } catch (RuntimeException ignored) {
                // A malformed local row should not make the whole tracker unavailable.
            }
        }
    }

    private void save() throws IOException {
        Path parent = dataFile.getParent();
        Files.createDirectories(parent);
        List<String> rows = new ArrayList<>();
        applications.values().forEach(application -> rows.add(row(application, "")));
        recentlyDeleted.values().forEach(deleted -> rows.add(row(deleted.application(), deleted.deletedAt().toString())));

        Path temporaryFile = Files.createTempFile(parent, "." + dataFile.getFileName(), ".tmp");
        try {
            Files.write(temporaryFile, rows, StandardCharsets.UTF_8);
            try {
                Files.move(temporaryFile, dataFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporaryFile, dataFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporaryFile);
        }
    }

    private static String row(Application application, String deletedAt) {
        return String.join("\t",
                Long.toString(application.id()), encode(application.company()), encode(application.role()),
                encode(application.location()), encode(application.source()), application.status().name(),
                encode(application.deadline()), encode(application.notes()), encode(String.join(",", application.skills())),
                application.createdAt().toString(), deletedAt);
    }

    private void purgeExpiredAndSave() throws IOException {
        if (purgeExpired()) save();
    }

    private List<Application> activeApplications(List<Long> ids) {
        List<Application> result = new ArrayList<>();
        for (long id : ids) {
            Application application = applications.get(id);
            if (application == null) return null;
            result.add(application);
        }
        return result;
    }

    private List<DeletedApplication> deletedApplications(List<Long> ids) {
        List<DeletedApplication> result = new ArrayList<>();
        for (long id : ids) {
            DeletedApplication deleted = recentlyDeleted.get(id);
            if (deleted == null) return null;
            result.add(deleted);
        }
        return result;
    }

    private static List<Long> requiredIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) throw new IllegalArgumentException("ids must contain at least one application ID.");
        Set<Long> uniqueIds = new HashSet<>();
        for (Long id : ids) {
            if (id == null || id <= 0) throw new IllegalArgumentException("ids must contain positive application IDs.");
            if (!uniqueIds.add(id)) throw new IllegalArgumentException("ids must not contain duplicate application IDs.");
        }
        return List.copyOf(ids);
    }

    private static Application.Status requiredStatus(String rawStatus) {
        if (rawStatus == null || rawStatus.isBlank()) throw new IllegalArgumentException("status is required.");
        return Application.Status.from(rawStatus);
    }

    private boolean purgeExpired() {
        Instant cutoff = clock.instant().minus(RECENTLY_DELETED_RETENTION);
        return recentlyDeleted.entrySet().removeIf(entry -> !entry.getValue().deletedAt().isAfter(cutoff));
    }

    private static String required(Map<String, String> input, String field, int maxLength) {
        String value = optional(input, field, maxLength);
        if (value.isBlank()) throw new IllegalArgumentException(field + " is required.");
        return value;
    }

    private static String optional(Map<String, String> input, String field, int maxLength) {
        String value = input.getOrDefault(field, "").trim();
        if (value.length() > maxLength) throw new IllegalArgumentException(field + " must be " + maxLength + " characters or fewer.");
        return value;
    }

    private static String deadline(String value) {
        if (value == null || value.isBlank()) return "";
        try {
            return LocalDate.parse(value).toString();
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException("Deadline must be a valid date.");
        }
    }

    private static List<String> skills(String rawSkills) {
        return java.util.Arrays.stream(rawSkills.split(","))
                .map(String::trim)
                .filter(skill -> !skill.isBlank())
                .map(skill -> skill.substring(0, 1).toUpperCase(Locale.ROOT) + skill.substring(1))
                .distinct()
                .limit(12)
                .collect(Collectors.toCollection(ArrayList::new));
    }

    private static String encode(String value) {
        return value.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", "\\r");
    }

    private static String decode(String value) {
        StringBuilder result = new StringBuilder();
        boolean escaping = false;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (escaping) {
                result.append(switch (character) { case 't' -> '\t'; case 'n' -> '\n'; case 'r' -> '\r'; default -> character; });
                escaping = false;
            } else if (character == '\\') escaping = true;
            else result.append(character);
        }
        if (escaping) result.append('\\');
        return result.toString();
    }

    public record DeletedApplication(Application application, Instant deletedAt) { }

    public record Dashboard(long total, Map<Application.Status, Long> byStatus, int responseRate) { }
}
