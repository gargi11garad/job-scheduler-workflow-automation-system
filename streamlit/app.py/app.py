import os
from datetime import datetime, timezone
import requests
import streamlit as st

st.set_page_config(page_title="Job Scheduler", page_icon="⏱️", layout="wide")
API_URL = os.getenv("API_URL", "http://localhost:8080")
try:
    API_URL = st.secrets.get("API_URL", API_URL)
except Exception:
    pass
API_URL = API_URL.rstrip("/")
STATUSES = ["ALL", "SCHEDULED", "RUNNING", "SUCCEEDED", "RETRY_WAIT", "FAILED", "CANCELLED"]

def request_jobs(status):
    params = {} if status == "ALL" else {"status": status}
    response = requests.get(f"{API_URL}/api/jobs", params=params, timeout=8)
    response.raise_for_status()
    return response.json()

st.title("⏱️ Job Scheduler")
st.caption("Schedule, retry, and monitor durable background jobs")
with st.sidebar:
    st.subheader("Connection")
    st.code(API_URL)
    status = st.selectbox("Filter jobs", STATUSES)
    if st.button("↻ Refresh", use_container_width=True):
        st.rerun()
left, right = st.columns([1, 2], gap="large")
with left:
    st.subheader("Schedule a job")
    with st.form("create_job", clear_on_submit=True):
        name = st.text_input("Job name", placeholder="daily-report")
        payload = st.text_area("Payload", placeholder="Generate the daily report")
        run_date = st.date_input("Run date (UTC)")
        run_time = st.time_input("Run time (UTC)")
        attempts = st.number_input("Max attempts", 1, 20, 3)
        delay = st.number_input("Retry delay (seconds)", 0, 86400, 30)
        submitted = st.form_submit_button("Schedule job", type="primary", use_container_width=True)
    if submitted:
        try:
            run_at = datetime.combine(run_date, run_time).replace(tzinfo=timezone.utc)
            response = requests.post(f"{API_URL}/api/jobs", json={"name": name, "payload": payload, "scheduledAt": run_at.isoformat(), "maxAttempts": int(attempts), "retryDelaySeconds": int(delay)}, timeout=8)
            response.raise_for_status()
            st.success("Job scheduled")
        except Exception as exc:
            st.error(f"Could not schedule job: {exc}")
with right:
    st.subheader("Job activity")
    try:
        jobs = request_jobs(status)
        metrics = st.columns(4)
        for col, key in zip(metrics, ["SCHEDULED", "RUNNING", "SUCCEEDED", "FAILED"]):
            col.metric(key.replace("_", " "), sum(j["status"] == key for j in jobs))
        if not jobs:
            st.info("No jobs found. Create one to get started.")
        for job in jobs:
            with st.container(border=True):
                c1, c2, c3 = st.columns([3, 2, 2])
                c1.markdown(f"**{job['name']}** · `{job['id'][:8]}`")
                c2.markdown(f"Status: **{job['status']}**")
                c3.caption(f"Attempts {job['attempts']}/{job['maxAttempts']}")
                st.caption(f"Scheduled: {job['scheduledAt']} · Payload: {job['payload']}")
                if job.get("lastError"):
                    st.error(f"Last error: {job['lastError']}")
                b1, b2 = st.columns(2)
                if job["status"] in ["SCHEDULED", "RETRY_WAIT"] and b1.button("Cancel", key=f"cancel-{job['id']}"):
                    requests.post(f"{API_URL}/api/jobs/{job['id']}/cancel", timeout=8).raise_for_status()
                    st.rerun()
                if job["status"] == "FAILED" and b2.button("Retry", key=f"retry-{job['id']}"):
                    requests.post(f"{API_URL}/api/jobs/{job['id']}/retry", timeout=8).raise_for_status()
                    st.rerun()
    except Exception as exc:
        st.error(f"API unavailable: {exc}")
        st.info("Deploy the Spring Boot API and set the Streamlit secret API_URL to its public URL.")
