# Job Scheduler & Workflow Automation System

Java 21 · Spring Boot · PostgreSQL · REST APIs · Streamlit

Durable background jobs with scheduling, lifecycle tracking, retries, cancellation, and a Streamlit operations dashboard.

## Run locally

```bash
docker compose up --build
```

Open the dashboard at http://localhost:8501 and the API at http://localhost:8080/api/jobs. Configure `API_URL` in Streamlit secrets for a hosted Spring Boot API.

Jobs move through `SCHEDULED`, `RUNNING`, `RETRY_WAIT`, `SUCCEEDED`, `FAILED`, and `CANCELLED`. A payload beginning with `fail:` simulates a failure to demonstrate retry handling.

See the API source and Compose configuration in this repository.
