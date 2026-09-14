# Co-op Compass

A local, full-stack command centre for keeping a co-op search organized. It lets a student track applications from saved job to offer, see response-rate analytics, search their pipeline, and keep useful notes and skill tags alongside each role.

## Features

- Kanban-style pipeline with one-click status updates
- Application search, status filtering, and deadline/company sorting
- Deadline radar and role-skill trend signals derived from your data
- Persistent weekly application goal (stored locally in your browser)
- Responsive, accessible interface with motion that respects reduced-motion settings
- CSV export for your personal application record
- Local TSV persistence: no account or data upload required

This first version deliberately has **zero third-party dependencies**: the backend is a Java HTTP API and the frontend is a responsive JavaScript single-page app. That makes it runnable on this computer today. The API boundary and project structure are intentional stepping stones toward a Spring Boot + React version later.

## What it demonstrates

- RESTful Java API design and input validation
- Persistent local storage, status transitions, and derived analytics
- Responsive frontend state management using `fetch`
- Accessible form labels, live error/status feedback, and keyboard-friendly controls
- Automated persistence test and Docker packaging

## Run it

From this folder, compile and start the app:

```bash
javac --add-modules jdk.httpserver -d out $(find src/main/java -name '*.java')
java --add-modules jdk.httpserver -cp out com.coopcompass.ApplicationServer
```

Then visit [http://localhost:8080](http://localhost:8080). Data is saved locally in `data/applications.tsv` (which is intentionally gitignored).

Run the lightweight test with:

```bash
javac --add-modules jdk.httpserver -d out $(find src/main/java src/test/java -name '*.java')
java --add-modules jdk.httpserver -ea -cp out com.coopcompass.ApplicationRepositoryTest
```

## Architecture

```text
Browser UI (HTML/CSS/JavaScript)
            |  fetch / JSON
Java HTTP server ── ApplicationRepository ── data/applications.tsv
```

`ApplicationServer` owns HTTP concerns and `ApplicationRepository` owns validation, IDs, persistence, and analytics. Keeping those responsibilities separate makes a later migration to Spring controllers and a database much simpler.

## Next upgrades

1. Replace the TSV repository with PostgreSQL and Flyway migrations.
2. Add a Spring Boot API, validation annotations, and JUnit tests.
3. Rebuild the UI with React/TypeScript and add login support.
4. Deploy the API and database, then record a short product demo for your résumé.

## Suggested résumé bullet

> Built Co-op Compass, a full-stack job-search dashboard using Java and JavaScript; designed REST endpoints, persistent application tracking, responsive analytics, and an accessible kanban workflow.
