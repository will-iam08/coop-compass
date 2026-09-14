package com.coopcompass;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

public final class ApplicationRepository {
    private final Path dataFile;
    private final Map<Long, Application> applications = new LinkedHashMap<>();
    private final AtomicLong nextId = new AtomicLong(1);

    public ApplicationRepository(Path dataFile) throws IOException {
        this.dataFile = dataFile;
        load();
    }

    public synchronized List<Application> list() {
        return applications.values().stream()
                .sorted(Comparator.comparing(Application::createdAt).reversed())
                .toList();
    }

    public synchronized Application create(Map<String, String> input) throws IOException {
        String company = required(input, "company", 80);
        String role = required(input, "role", 100);
        String location = optional(input, "location", 80);
        String source = optional(input, "source", 60);
        String notes = optional(input, "notes", 1000);
        String deadline = deadline(input.getOrDefault("deadline", ""));
        Application.Status status = Application.Status.from(input.get("status"));
        List<String> skills = skills(input.getOrDefault("skills", ""));

        Application application = new Application(nextId.getAndIncrement(), company, role, location, source, status,
                deadline, notes, skills, Instant.now());
        applications.put(application.id(), application);
        save();
        return application;
    }

    public synchronized Optional<Application> updateStatus(long id, String rawStatus) throws IOException {
        Application existing = applications.get(id);
        if (existing == null) return Optional.empty();
        Application updated = existing.withStatus(Application.Status.from(rawStatus));
        applications.put(id, updated);
        save();
        return Optional.of(updated);
    }

    public synchronized boolean delete(long id) throws IOException {
        if (applications.remove(id) == null) return false;
        save();
        return true;
    }

    public synchronized Dashboard dashboard() {
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
            if (values.length != 10) continue;
            long id = Long.parseLong(values[0]);
            Application application = new Application(id, decode(values[1]), decode(values[2]), decode(values[3]),
                    decode(values[4]), Application.Status.valueOf(values[5]), decode(values[6]), decode(values[7]),
                    skills(decode(values[8])), Instant.parse(values[9]));
            applications.put(id, application);
            nextId.set(Math.max(nextId.get(), id + 1));
        }
    }

    private void save() throws IOException {
        Path parent = dataFile.getParent();
        if (parent != null) Files.createDirectories(parent);
        List<String> rows = applications.values().stream().map(application -> String.join("\t",
                Long.toString(application.id()), encode(application.company()), encode(application.role()),
                encode(application.location()), encode(application.source()), application.status().name(),
                encode(application.deadline()), encode(application.notes()), encode(String.join(",", application.skills())),
                application.createdAt().toString())).toList();
        Files.write(dataFile, rows, StandardCharsets.UTF_8);
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

    public record Dashboard(long total, Map<Application.Status, Long> byStatus, int responseRate) { }
}
