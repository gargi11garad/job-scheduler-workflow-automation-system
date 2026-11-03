package com.example.scheduler;

import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.*;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

@SpringBootApplication
@EnableScheduling
public class SchedulerApplication {
    public static void main(String[] args) { SpringApplication.run(SchedulerApplication.class, args); }
    @Bean CommandLineRunner createSchema(JdbcTemplate db) { return args -> db.execute("CREATE TABLE IF NOT EXISTS jobs (id UUID PRIMARY KEY, name VARCHAR(160) NOT NULL, payload TEXT NOT NULL, status VARCHAR(24) NOT NULL, scheduled_at TIMESTAMPTZ NOT NULL, created_at TIMESTAMPTZ NOT NULL, updated_at TIMESTAMPTZ NOT NULL, attempts INT NOT NULL DEFAULT 0, max_attempts INT NOT NULL, retry_delay_seconds BIGINT NOT NULL, last_error TEXT, started_at TIMESTAMPTZ, finished_at TIMESTAMPTZ)"); }
}

record CreateJob(String name, String payload, Instant scheduledAt, Integer maxAttempts, Long retryDelaySeconds) {}

@RestController @RequestMapping("/api/jobs")
class JobController {
    private final JdbcTemplate db;
    private static final String COLS="id,name,payload,status,scheduled_at AS \"scheduledAt\",created_at AS \"createdAt\",updated_at AS \"updatedAt\",attempts,max_attempts AS \"maxAttempts\",retry_delay_seconds AS \"retryDelaySeconds\",last_error AS \"lastError\",started_at AS \"startedAt\",finished_at AS \"finishedAt\"";
    JobController(JdbcTemplate db) { this.db=db; }
    @PostMapping @ResponseStatus(HttpStatus.CREATED) Map<String,Object> create(@RequestBody CreateJob r) {
        if(r.name()==null || r.name().isBlank() || r.payload()==null || r.payload().isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"name and payload are required");
        int max=r.maxAttempts()==null?3:r.maxAttempts(); long delay=r.retryDelaySeconds()==null?30:r.retryDelaySeconds();
        if(max<1 || max>20 || delay<0 || delay>86400) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"retry settings out of range");
        UUID id=UUID.randomUUID(); Timestamp now=Timestamp.from(Instant.now()); Timestamp at=Timestamp.from(r.scheduledAt()==null?Instant.now():r.scheduledAt());
        db.update("INSERT INTO jobs(id,name,payload,status,scheduled_at,created_at,updated_at,attempts,max_attempts,retry_delay_seconds) VALUES (?,?,?,'SCHEDULED',?,?,?,0,?,?)",id,r.name(),r.payload(),at,now,now,max,delay); return get(id);
    }
    @GetMapping List<Map<String,Object>> list(@RequestParam(required=false) String status) {
        if(status==null) return db.queryForList("SELECT "+COLS+" FROM jobs ORDER BY created_at DESC");
        return db.queryForList("SELECT "+COLS+" FROM jobs WHERE status=? ORDER BY created_at DESC",status.toUpperCase());
    }
    @GetMapping("/health") Map<String,String> health() { return Map.of("status","UP","service","job-scheduler"); }
    @GetMapping("/{id}") Map<String,Object> get(@PathVariable UUID id) {
        List<Map<String,Object>> rows=db.queryForList("SELECT "+COLS+" FROM jobs WHERE id=?",id); if(rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Job not found"); return rows.get(0);
    }
    @PostMapping("/{id}/cancel") Map<String,Object> cancel(@PathVariable UUID id) {
        int n=db.update("UPDATE jobs SET status='CANCELLED',updated_at=NOW(),finished_at=NOW() WHERE id=? AND status IN ('SCHEDULED','RETRY_WAIT')",id); if(n==0) throw new ResponseStatusException(HttpStatus.CONFLICT,"Job is not cancellable"); return get(id);
    }
    @PostMapping("/{id}/retry") @ResponseStatus(HttpStatus.CREATED) Map<String,Object> retry(@PathVariable UUID id) {
        Map<String,Object> old=get(id); if(!"FAILED".equals(old.get("status"))) throw new ResponseStatusException(HttpStatus.CONFLICT,"Only failed jobs can be retried");
        return create(new CreateJob(old.get("name").toString(),old.get("payload").toString(),Instant.now(),((Number)old.get("maxAttempts")).intValue(),((Number)old.get("retryDelaySeconds")).longValue()));
    }
}

@Component class JobWorker {
    private final JdbcTemplate db; JobWorker(JdbcTemplate db) { this.db=db; }
    @Scheduled(fixedDelayString="${scheduler.poll-ms:3000}") void poll() {
        List<Map<String,Object>> due=db.queryForList("SELECT id FROM jobs WHERE status IN ('SCHEDULED','RETRY_WAIT') AND scheduled_at<=NOW() ORDER BY scheduled_at LIMIT 25");
        for(Map<String,Object> row:due) execute((UUID)row.get("id"));
    }
    void execute(UUID id) {
        int claimed=db.update("UPDATE jobs SET status='RUNNING',attempts=attempts+1,started_at=NOW(),updated_at=NOW() WHERE id=? AND status IN ('SCHEDULED','RETRY_WAIT') AND scheduled_at<=NOW()",id); if(claimed==0) return;
        Map<String,Object> job=db.queryForMap("SELECT payload,attempts,max_attempts,retry_delay_seconds FROM jobs WHERE id=?",id);
        try { String payload=job.get("payload").toString(); if(payload.startsWith("fail:")) throw new IllegalStateException(payload.substring(5));
            db.update("UPDATE jobs SET status='SUCCEEDED',last_error=NULL,updated_at=NOW(),finished_at=NOW() WHERE id=?",id);
        } catch(Exception e) {
            db.update("UPDATE jobs SET status=CASE WHEN attempts<max_attempts THEN 'RETRY_WAIT' ELSE 'FAILED' END,last_error=?,updated_at=NOW(),scheduled_at=CASE WHEN attempts<max_attempts THEN NOW()+(retry_delay_seconds*INTERVAL '1 second') ELSE scheduled_at END,finished_at=CASE WHEN attempts<max_attempts THEN NULL ELSE NOW() END WHERE id=?",e.getMessage()==null?"Job failed":e.getMessage(),id);
        }
    }
}
